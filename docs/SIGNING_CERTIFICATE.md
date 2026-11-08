# Velora release certificate

Status: **not created or supplied**. There is no trusted release fingerprint yet.
No key or fingerprint has been invented for this setup.

Create the permanent external key with `scripts/create-release-key.sh` in your own
terminal. Its interactive registration step writes the actual public SHA-256
fingerprint both here and to `release/certificate.sha256`. Review and commit those
public changes before the first stable release. Keep the private key and passwords
outside Git. See [RELEASING.md](RELEASING.md) for backup and recovery.
