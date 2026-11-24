// The only way this app opens a connection to a class: TLS 1.3 to a server whose certificate public key
// matches the pinned instructor key (the same check Agent.Pinned does in Java).
// Node cannot verify a self-signed certificate against a CA, so the CA check is off and the pin below is the
// real check - this is pinning, never "trust anything". Callers must wait for the 'pinned' event before they
// write: data written earlier would be flushed as soon as the handshake ends, before the key was compared.
const tls = require('node:tls');
const crypto = require('node:crypto');

const hex = (s) => String(s ?? '').toLowerCase().replace(/[^0-9a-f]/g, '');

/** SHA-256 of the certificate's SubjectPublicKeyInfo: the value LabServer.Identity shows the lecturer. */
function keyFingerprint(rawDer) {
  const spki = new crypto.X509Certificate(rawDer).publicKey.export({ format: 'der', type: 'spki' });
  return crypto.createHash('sha256').update(spki).digest('hex');
}

/** A TLS socket that emits 'pinned' once the certificate is the pinned one, and is destroyed if it is not. */
function connectPinned({ host, port, pin }) {
  const want = hex(pin);
  const s = tls.connect({ host, port, minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3', rejectUnauthorized: false });
  s.once('secureConnect', () => {
    let got = '';
    try { got = keyFingerprint(s.getPeerCertificate()?.raw); } catch { got = ''; }
    if (want.length < 16 || !got.startsWith(want)) {
      s.destroy(new Error('That computer is not the instructor you pinned (its key does not match)'));
      return;
    }
    s.emit('pinned');
  });
  return s;
}

module.exports = { connectPinned, keyFingerprint, hex };
