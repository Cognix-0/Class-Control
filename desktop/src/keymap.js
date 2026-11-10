// Browser KeyboardEvent.code -> Java KeyEvent.VK_* (what the agent's Robot understands).
const map = {
  Enter: 10, NumpadEnter: 10, Backspace: 8, Tab: 9, Escape: 27, Space: 32,
  ShiftLeft: 16, ShiftRight: 16, ControlLeft: 17, ControlRight: 17, AltLeft: 18, AltRight: 18,
  // Cmd on a Mac instructor -> Ctrl on the (Windows) student PC, so Cmd+C / Cmd+V do what you expect.
  MetaLeft: 17, MetaRight: 17,
  CapsLock: 20, PageUp: 33, PageDown: 34, End: 35, Home: 36,
  ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40,
  Insert: 155, Delete: 127,
  Comma: 44, Minus: 45, Period: 46, Slash: 47, Semicolon: 59, Equal: 61,
  BracketLeft: 91, Backslash: 92, BracketRight: 93, Backquote: 192, Quote: 222,
  NumpadMultiply: 106, NumpadAdd: 107, NumpadSubtract: 109, NumpadDecimal: 110, NumpadDivide: 111,
};
for (let i = 0; i < 26; i++) map[`Key${String.fromCharCode(65 + i)}`] = 65 + i;
for (let i = 0; i <= 9; i++) { map[`Digit${i}`] = 48 + i; map[`Numpad${i}`] = 96 + i; }
for (let i = 1; i <= 12; i++) map[`F${i}`] = 111 + i;

export const javaKey = (code) => map[code];
