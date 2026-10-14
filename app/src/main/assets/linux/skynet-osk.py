#!/usr/bin/env python3
"""SkyNet's on-screen keyboard for the Linux desktop.

A GTK window that never takes the keyboard focus and types through the XTEST extension of the app's X server, so the
Android keyboard is not needed. A small button in the corner shows and hides it. Shift, Ctrl and Alt stay down for one key.
Holding a key repeats it (the X server does not repeat keys by itself).
"""
import ctypes
import os
import sys

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, GLib, Gtk  # noqa: E402

x11 = ctypes.CDLL("libX11.so.6")
xtst = ctypes.CDLL("libXtst.so.6")
x11.XOpenDisplay.restype = ctypes.c_void_p
x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
x11.XStringToKeysym.restype = ctypes.c_ulong
x11.XStringToKeysym.argtypes = [ctypes.c_char_p]
x11.XKeysymToKeycode.restype = ctypes.c_ubyte
x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
x11.XFlush.argtypes = [ctypes.c_void_p]
xtst.XTestFakeKeyEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]

display = x11.XOpenDisplay(None)
if not display:
    sys.exit("skynet-osk: cannot open the display")

SHIFTED = {
    "1": "!", "2": "@", "3": "#", "4": "$", "5": "%", "6": "^", "7": "&", "8": "*", "9": "(", "0": ")",
    "-": "_", "=": "+", "[": "{", "]": "}", "\\": "|", ";": ":", "'": '"', ",": "<", ".": ">", "/": "?", "`": "~",
}
SYMBOL_NAMES = {
    "-": "minus", "=": "equal", "[": "bracketleft", "]": "bracketright", "\\": "backslash", ";": "semicolon",
    "'": "apostrophe", ",": "comma", ".": "period", "/": "slash", "`": "grave",
}


def key(label, keysym=None, width=4, kind="key"):
    return {"label": label, "keysym": keysym or SYMBOL_NAMES.get(label, label), "width": width, "kind": kind}


ROWS = [
    [key("Esc", "Escape", 4)] + [key(c) for c in "1234567890-="] + [key("⌫", "BackSpace", 8)],
    [key("Tab", "Tab", 8)] + [key(c) for c in "qwertyuiop[]\\"],
    [key("", None, 8, "space")] + [key(c) for c in "asdfghjkl;'"] + [key("Enter", "Return", 8)],
    [key("Shift", "Shift_L", 10, "mod")] + [key(c) for c in "zxcvbnm,./"] + [key("Shift", "Shift_R", 10, "mod")],
    [key("Ctrl", "Control_L", 6, "mod"), key("Alt", "Alt_L", 6, "mod"), key("space", "space", 24),
     key("←", "Left"), key("↑", "Up"), key("↓", "Down"), key("→", "Right"),
     key("Del", "Delete"), key("▼", None, 4, "hide")],
]

CSS = b"""
window { background: #1b2230; }
button { background: #2b3448; color: #e8ecf5; border: 1px solid #0f141f; border-radius: 6px; padding: 0; min-height: 0; min-width: 0; font-size: 16pt; }
button:active { background: #4a6ea9; }
button.mod:checked { background: #3b7bd8; }
button.corner { background: #3b7bd8; font-size: 18pt; }
"""

keycodes = {}
SHOW_FILE = "/tmp/skynet-osk-show"


def keycode(name):
    if name not in keycodes:
        keycodes[name] = x11.XKeysymToKeycode(display, x11.XStringToKeysym(name.encode()))
    return keycodes[name]


def send(name, down):
    code = keycode(name)
    if code:
        xtst.XTestFakeKeyEvent(display, code, 1 if down else 0, 0)
        x11.XFlush(display)


class Keyboard:
    def __init__(self):
        geometry = Gdk.Display.get_default().get_monitor(0).get_geometry()
        self.screen_width, self.screen_height = geometry.width, geometry.height
        provider = Gtk.CssProvider()
        provider.load_from_data(CSS)
        Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

        self.held = {}          # modifier keysym -> button
        self.letters = []       # (button, key) for the labels that change with Shift
        self.repeat = None
        self.window = self.make_window(Gdk.WindowTypeHint.DOCK)
        self.build_keys()
        width = int(self.screen_width * 0.82)
        height = int(width * 0.30)
        self.window.set_default_size(width, height)
        self.window.move((self.screen_width - width) // 2, self.screen_height - height - 8)

        self.toggle = self.make_window(Gdk.WindowTypeHint.DOCK)
        button = Gtk.Button(label="⌨")
        button.get_style_context().add_class("corner")
        button.set_can_focus(False)
        button.connect("clicked", lambda *_: self.set_visible(not self.window.get_visible()))
        self.toggle.add(button)
        size = max(44, int(self.screen_width * 0.035))
        self.toggle.set_default_size(size, size)
        self.toggle.move(8, self.screen_height - size - 8)
        self.toggle.show_all()
        # The app creates this file when a text field was tapped.
        GLib.timeout_add(300, self.check_show_request)

    def check_show_request(self):
        if os.path.exists(SHOW_FILE):
            try:
                os.remove(SHOW_FILE)
            except OSError:
                pass
            if not self.window.get_visible():
                self.set_visible(True)
        return True

    @staticmethod
    def make_window(hint):
        window = Gtk.Window()
        window.set_type_hint(hint)
        window.set_accept_focus(False)
        window.set_focus_on_map(False)
        window.set_keep_above(True)
        window.set_skip_taskbar_hint(True)
        window.set_skip_pager_hint(True)
        window.set_decorated(False)
        return window

    def set_visible(self, visible):
        if visible:
            self.window.show_all()
        else:
            self.window.hide()

    def build_keys(self):
        grid = Gtk.Grid()
        grid.set_row_homogeneous(True)
        grid.set_column_homogeneous(True)
        grid.set_row_spacing(4)
        grid.set_column_spacing(4)
        grid.set_border_width(6)
        for row_index, row in enumerate(ROWS):
            column = 0
            for spec in row:
                if spec["kind"] == "space":
                    column += spec["width"]
                    continue
                button = Gtk.ToggleButton(label=spec["label"]) if spec["kind"] == "mod" else Gtk.Button(label=spec["label"])
                button.set_can_focus(False)
                button.set_focus_on_click(False)
                if spec["kind"] == "mod":
                    button.get_style_context().add_class("mod")
                    button.connect("toggled", self.on_modifier, spec)
                elif spec["kind"] == "hide":
                    button.connect("clicked", lambda *_: self.set_visible(False))
                else:
                    button.connect("pressed", self.on_press, spec)
                    button.connect("released", self.on_release, spec)
                    if len(spec["label"]) == 1:
                        self.letters.append((button, spec))
                grid.attach(button, column, row_index, spec["width"], 1)
                column += spec["width"]
        self.window.add(grid)

    def shift_on(self):
        return any(name.startswith("Shift") for name in self.held)

    def relabel(self):
        shift = self.shift_on()
        for button, spec in self.letters:
            label = spec["label"]
            if label.isalpha():
                button.set_label(label.upper() if shift else label)
            elif label in SHIFTED:
                button.set_label(SHIFTED[label] if shift else label)

    def on_modifier(self, button, spec):
        name = spec["keysym"]
        if button.get_active():
            self.held[name] = button
            send(name, True)
        else:
            self.held.pop(name, None)
            send(name, False)
        self.relabel()

    def release_modifiers(self):
        for name, button in list(self.held.items()):
            button.set_active(False)   # sends the release through on_modifier

    def on_press(self, button, spec):
        name = spec["keysym"]
        send(name, True)
        self.cancel_repeat()
        self.repeat = GLib.timeout_add(450, self.start_repeat, name)

    def start_repeat(self, name):
        self.repeat = GLib.timeout_add(45, self.repeat_key, name)
        return False

    def repeat_key(self, name):
        send(name, False)
        send(name, True)
        return True

    def cancel_repeat(self):
        if self.repeat is not None:
            GLib.source_remove(self.repeat)
            self.repeat = None

    def on_release(self, button, spec):
        self.cancel_repeat()
        send(spec["keysym"], False)
        if self.held:
            self.release_modifiers()


def main():
    Keyboard()
    Gtk.main()


if __name__ == "__main__":
    main()
