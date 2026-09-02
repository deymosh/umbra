# Security Policy

## Reporting Security Vulnerabilities

If you discover a security vulnerability in Umbra, **please do not open a public GitHub issue**. Instead, report it privately so we can address it before public disclosure.

### How to report

1. **GitHub Private Security Disclosure** — If you have a GitHub account:
   - Go to the [Umbra repository](https://github.com/deymosh/umbra)
   - Click "Security" tab
   - Click "Report a vulnerability" 
   - Follow the template

2. **What to include**
   - Type of vulnerability (e.g., TOR bypass, key leak, privacy leak)
   - Affected component (e.g., NetworkModule, AmberSignerGateway, Room queries)
   - Severity (Critical / High / Medium / Low)
   - Proof of concept (code snippet or steps)
   - Your name/GitHub username (optional)

### Response timeline

- **Critical** (e.g., TOR bypass, private key leak) — Response within 24 hours
- **High** (e.g., information leak, auth bypass) — Response within 48 hours
- **Medium/Low** (e.g., UI issue, minor privacy concern) — Response within 1 week

We will:
1. Acknowledge receipt of your report
2. Investigate and confirm the vulnerability
3. Develop a fix
4. Provide a timeline for release
5. Credit you in the fix commit (unless you prefer anonymity)

### Embargo period

We ask that you do not disclose the vulnerability publicly until:
- A fix is released, OR
- 90 days have passed since your report (whichever is earlier)

This gives us time to develop and deploy a patch before attackers can exploit the issue.

## Security Assurances

Umbra maintains security through:

### Code review

- All changes reviewed for TOR routing, privacy, and crypto correctness
- Mandatory security checklist before merge (see [CONTRIBUTING.md](CONTRIBUTING.md))
- Automated tests verify no insecure APIs are used

### Testing

- Unit tests for all security-critical paths
- Integration tests for TOR connectivity
- Signature verification tests (BIP-340)
- Key handling tests (no nsec leaks)

### Architecture

- **Mandatory TOR routing** — `TorProxyConfig.isReady` checked before network calls
- **No plaintext keys on device** — Signing delegated to Amber
- **Encrypted local storage** — Sensitive data encrypted with Android Keystore
- **Logging scrubber** — Relay URLs, pubkeys, event content scrubbed from logs
- **BIP-340 verification** — All events cryptographically verified before caching

### Threat model

Umbra protects against:
- IP-based identification (via TOR)
- ISP/network observer attacks (via TOR)
- Relay operator correlation across devices
- Device fingerprinting (no user agent, no cookies)
- Plaintext eavesdropping (TLS enforced)
- App-imposed censorship — moderation (muting, NSFW hiding, feed content filters) is user-owned, editable, and fully removable state, never a hardcoded app-side rule

Umbra does NOT protect against:
- Malware on your device
- Quantum computing attacks (affects all Nostr clients)
- Compromised Amber signer
- Relay operators analyzing event content (this is inherent to Nostr)
- Moderation by relays or other users (a relay can refuse your notes, other clients can mute you) — outside Umbra's control

## Known Limitations

### Current limitations

- **Android 8.0+ only** — Older devices not supported
- **Requires Orbot** — No TOR, no network connections (by design)
- **Amber required for signing** — Read-only mode only for anonymous browsing
- **End-to-end DM encryption is still incomplete** — Umbra has NIP-17 transport and relay-list scaffolding, but the full NIP-44/NIP-59 encrypted messaging UX is not yet shipped and relay operators can still observe metadata/content conventions unless the application-level pipeline is complete

### Future security improvements

We are exploring:
- Hardware key support (YubiKey-style)
- Biometric signing
- Decentralized identity verification (not NIP-05)

## Dependencies

Umbra uses the following security-relevant dependencies:

| Library | Version | Purpose | Vulnerability notes |
|---------|---------|---------|---------------------|
| BouncyCastle | 1.85.2 | BIP-340 Schnorr signing | Actively maintained, no known exploits |
| OkHttp | 5.4.0 | HTTP client with proxy | Maintained by Square, widely used |
| Room | 2.8.4 | Local database | Part of Android Jetpack, maintained by Google |
| AndroidKeystore | Built-in | Encryption | Part of Android OS, not vulnerable to app bypass |

For an up-to-date list of all dependencies, see `app/build.gradle.kts`.

## Audit

This codebase includes [AUDIT.md](AUDIT.md), which documents all security rules and is enforced via automated tests.

Key audit areas:
- Network layer (TOR enforcement)
- Crypto layer (BIP-340 verification)
- Key management (no nsec storage)
- Logging (data scrubbing)
- Architecture (layer separation)
- Persistence (encryption, event verification)

## Bug Bounty

Currently, Umbra does not have a formal bug bounty program. However:

- We deeply appreciate security research and responsible disclosure
- We will credit researchers in fix commits (with permission)
- We may offer compensation for critical vulnerabilities (case-by-case)

Contact us privately if you have found a critical issue and would like to discuss compensation.

## Security Updates

- Once Umbra has a public release, security updates will be released as patch versions
- Users will be notified in the app when a security update is available
- Critical updates may be released as hotfixes

Subscribe to the repo's release notifications to stay informed once releases begin.

## License

Umbra is open source under the [MIT license](LICENSE). The security of open source depends on community review. If you audit the code, please let us know what you find.

---

Thank you for helping keep Umbra secure. 🔒
