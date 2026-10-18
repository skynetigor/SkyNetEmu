-- Run from BeamNG's BinLinux directory:
--   console.x64 file /path/to/beamng-physics-bench.lua
-- Use the same emulator, app launch path and CPU policy for each comparison.
-- This needs the installed game's Lua modules; it contains no game implementation.
-- It measures physics without traffic AI or graphics, not gameplay FPS.

local first = tonumber(os.getenv("SKY_BENCH_VEHICLE_MIN") or "5")
local last = tonumber(os.getenv("SKY_BENCH_VEHICLE_MAX") or "5")
assert(first and last and first >= 1 and last >= first
  and first % 1 == 0 and last % 1 == 0, "Invalid vehicle count range")

print("SKY_BENCH_BEGIN")
local bench = require("lua/console/bananabench")
local originalStep = assert(myBenchStep, "Unsupported BeamNG benchmark API")

-- The built-in helper returns minimum batch duration times batch count.
-- Record that value, but use elapsed time minus spawning for sustained throughput.
-- Warmup calls are recorded separately with their actual batch count.
function myBenchStep(vehicle, data, count, steps)
  local timer = HighPerfTimer()
  local spawnTime, peakTime = originalStep(vehicle, data, count, steps)
  local elapsed = timer:stop()
  assert(elapsed > spawnTime, "Invalid benchmark timing")
  print("SKY_ELAPSED " .. jsonEncode({
    vehicles = count,
    batches = steps or 200,
    elapsed = elapsed,
    peakTime = peakTime,
    spawnTime = spawnTime
  }))
  return spawnTime, elapsed - spawnTime
end

local ok, results = pcall(bench.physics, {"pickup"}, first, last)
myBenchStep = originalStep
if not ok then error(results) end
print("SKY_BENCH_RESULT " .. jsonEncode(results))
print("SKY_BENCH_END")
