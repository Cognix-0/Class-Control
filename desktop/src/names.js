// How a PC is named in the UI: the logged-in student first, then the computer.
// A conversation follows the student (or the PC when nobody is logged in), not the connection, so it survives reconnects.
export const threadKey = (pc) => pc.student || pc.host;

export const who = (pc) => (pc.student ? `${pc.student} · ${pc.host}` : pc.host);
