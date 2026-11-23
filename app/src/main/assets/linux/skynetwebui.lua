-- serves the game's own UI to a browser/WebView over HTTP + WebSocket (the UI's "external client mode", comms.js) on 127.0.0.1: the app shows it as an overlay
local M = {}
local port = 8084
local server
local origTrigger
local stats = os.getenv('SKY_WEBUI_STATS') and {n = {}, bytes = {}, t = 0} or nil   -- SKY_WEBUI_STATS=1: log what the forwarder sends
local noForward = os.getenv('SKY_WEBUI_NOFWD') ~= nil
local noRaw = os.getenv('SKY_WEBUI_NORAW') ~= nil   -- SKY_WEBUI_NORAW=1: do not forward the hooks sent as JSON text (triggerRawJS)

-- The engine's web server answers hooks fired while it runs a page's command (GL...) only when that command returns (a level load is one command, so
-- the loading screen's progress would arrive after the load) and drops the ones fired anywhere else (route data, snapshots): the stock UI gets all
-- of them through CEF. Broadcast every hook to the connected pages right away; a command's own reply stays with the engine.
local origTriggerRaw
local function installHookForward()
  if origTrigger or not guihooks or not guihooks.trigger then return end
  -- hooks whose payload is already JSON text (guihooks.triggerRawJS(name, json): 0.39's controls service sends "ControllersChanged" this way, and the page's binding code fails without it)
  if guihooks.triggerRawJS and not noRaw then
    origTriggerRaw = guihooks.triggerRawJS
    guihooks.triggerRawJS = function(name, rawJs, ...)
      origTriggerRaw(name, rawJs, ...)
      if server and name ~= 'onBNGAPICallback' and not noForward then
        local ok, hook = pcall(jsonEncode, name)
        if ok then
          local msg = 'H#[[' .. hook .. ',[' .. tostring(rawJs) .. ']]]'
          if stats then stats.n[name] = (stats.n[name] or 0) + 1; stats.bytes[name] = (stats.bytes[name] or 0) + #msg end
          server:broadcastData(msg)
        end
      end
    end
  end
  origTrigger = guihooks.trigger
  guihooks.trigger = function(name, ...)
    origTrigger(name, ...)
    if server and name ~= 'onBNGAPICallback' and not noForward then
      local args = {}
      for i = 1, select('#', ...) do
        local ok, j = pcall(jsonEncode, (select(i, ...)))
        args[i] = ok and j or 'null'
      end
      local ok, hook = pcall(jsonEncode, name)
      if ok then
        local msg = 'H#[[' .. hook .. ',[' .. table.concat(args, ',') .. ']]]'
        if stats then stats.n[name] = (stats.n[name] or 0) + 1; stats.bytes[name] = (stats.bytes[name] or 0) + #msg end
        server:broadcastData(msg)
      end
    end
  end
end
-- a level load unloads ordinary extensions, which would stop the server (and the page with it): keep this one across levels
local function onInit()
  setExtensionUnloadMode(M, "manual")
end
-- No CEF means server.createGame runs its level callback inside the first loading-screen request, and only
-- then asks for worldReadyState. The screen therefore hides when the level appears and comes back while the
-- vehicle materials finish. Hold worldReadyState first so that exit cannot show the world early.
local function installLoadHold()
  local gs = rawget(_G, 'core_gamestate')
  if not gs or gs.__skyHold or type(gs.requestEnterLoadingScreen) ~= 'function' then return false end
  local orig = gs.requestEnterLoadingScreen
  gs.requestEnterLoadingScreen = function(tagName, func, ...)
    if func and tagName == 'server.lua' and not (gs.getLoadingStatus and gs.getLoadingStatus('worldReadyState')) then
      orig('worldReadyState')
    end
    return orig(tagName, func, ...)
  end
  gs.__skyHold = true
  log('I', 'skynetwebui', 'worldReadyState held across the synchronous level load')
  return true
end

-- A second freeroam start while the level is still loaded unloads it and loads it again (the menu's delayed-start
-- path). A pad press and the page's own play button can both send that command; the world is on screen for a
-- second or two, then the loading screen returns. Ignore that second start. A later start, or one after the
-- level has been unloaded, still goes through.
local function levelKey(level)
  if type(level) == 'table' then return tostring(level.levelName or level.misFilePath or level) end
  return tostring(level)
end

local function installStartGuard()
  local fr = rawget(_G, 'freeroam_freeroam')
  if not fr or fr.__skyGuard or type(fr.startFreeroam) ~= 'function' then return false end
  local orig = fr.startFreeroam
  fr.startFreeroam = function(level, startPointName, wasDelayed, ...)
    local key = levelKey(level)
    local now = os.clock()
    -- wasDelayed is the game's own retry after it unloaded the previous level; that one must run.
    if not wasDelayed and fr.__skyLevel == key and fr.__skyAt and (now - fr.__skyAt) < 180
        and scenetree and scenetree.MissionGroup then
      log('I', 'skynetwebui', 'ignored a second freeroam start for ' .. key .. ' while that level is still loaded')
      return
    end
    fr.__skyLevel = key
    fr.__skyAt = now
    return orig(level, startPointName, wasDelayed, ...)
  end
  fr.__skyGuard = true
  log('I', 'skynetwebui', 'freeroam start guard installed')
  return true
end

local function installLoadFixes()
  installLoadHold()
  installStartGuard()
end

local function onExtensionLoaded()
  server = BNGWebWSServer.getOrCreate('127.0.0.1', port, './', 'bng-ext-app-v1', '/ui/entrypoints/main/index.html', false, false)
  if not os.getenv('SKY_WEBUI_NOSTREAMS') then server:enableDataStreams() end
  installHookForward()
  installLoadFixes()
  print('skynetwebui: ui server on http://127.0.0.1:' .. tostring(port) .. '/')
end
local function onExtensionUnloaded()
  if server then BNGWebWSServer.destroy(server); server = nil end
end
local function onUpdate(dtReal)
  installLoadFixes()
  if not server then return end
  server:getPeerEvents()
  if stats then
    stats.t = stats.t + (dtReal or 0)
    if stats.t > 5 then
      local rows = {}
      for k, n in pairs(stats.n) do rows[#rows + 1] = {k, n, stats.bytes[k]} end
      table.sort(rows, function(a, b) return a[3] > b[3] end)
      local out = {}
      for i = 1, math.min(6, #rows) do out[#out + 1] = rows[i][1] .. ' x' .. rows[i][2] .. ' ' .. rows[i][3] .. 'B' end
      log('I', 'skynetwebui', 'hooks in ' .. string.format('%.1f', stats.t) .. 's: ' .. table.concat(out, ', '))
      stats.n = {}; stats.bytes = {}; stats.t = 0
    end
  end
end
M.onInit = onInit
M.onExtensionLoaded = onExtensionLoaded
M.onExtensionUnloaded = onExtensionUnloaded
M.onUpdate = onUpdate
return M
