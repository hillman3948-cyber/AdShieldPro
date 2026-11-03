"""Local key material, Ed25519 signatures and AES-256-GCM encryption.

Threat-intel feeds are signed (Ed25519) and verified before activation. Free-text
user notes and assistant transcripts are encrypted at rest with AES-256-GCM.
Private keys and tokens live only in ``aether/data/`` (git-ignored, mode 0600);
public verification keys are committed so a fresh clone can verify the bundled
feed without any secret material.
"""
from __future__ import annotations

import base64
import hashlib
import os
import secrets
from pathlib import Path

try:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import (
        Ed25519PrivateKey, Ed25519PublicKey,
    )
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError as exc:  # pragma: no cover - dependency guard
    raise RuntimeError(
        "AETHER needs the 'cryptography' package. Run aether/run.sh or "
        "`pip install -r aether/requirements.txt`."
    ) from exc

SIGNATURE_ALGORITHM = "Ed25519"


def _write_secret(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)


class KeyStore:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.data_dir = self.root / "data"
        self.keys_dir = self.data_dir / "keys"
        self.bundled_keys_dir = self.root / "intel" / "trusted_keys"
        self.runtime_keys_dir = self.data_dir / "trusted_keys"
        self.keys_dir.mkdir(parents=True, exist_ok=True)
        self.runtime_keys_dir.mkdir(parents=True, exist_ok=True)

        self.signing_key_path = self.keys_dir / "intel_signing_key.pem"
        self.public_key_path = self.keys_dir / "intel_signing_public.pem"
        self.encryption_key_path = self.keys_dir / "encryption.key"
        self.api_token_path = self.data_dir / "api.token"
        self.admin_token_path = self.data_dir / "admin.token"

        self._signing_key = self._load_or_create_signing_key()
        self._encryption_key = self._load_or_create_encryption_key()
        self.api_token = os.environ.get("AETHER_API_TOKEN") or self._load_or_create_token(
            self.api_token_path, "AETHER_API_TOKEN"
        )
        self.admin_token = os.environ.get("AETHER_ADMIN_TOKEN") or self._load_or_create_token(
            self.admin_token_path, "AETHER_ADMIN_TOKEN"
        )

    def _load_or_create_signing_key(self) -> Ed25519PrivateKey:
        if self.signing_key_path.exists():
            return serialization.load_pem_private_key(
                self.signing_key_path.read_bytes(), password=None
            )
        key = Ed25519PrivateKey.generate()
        private_pem = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        public_pem = key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        _write_secret(self.signing_key_path, private_pem)
        self.public_key_path.write_bytes(public_pem)
        self._publish_runtime_public_key(public_pem)
        return key

    def _publish_runtime_public_key(self, public_pem: bytes) -> None:
        fingerprint = self._fingerprint_bytes(public_pem)[:16]
        target = self.runtime_keys_dir / f"runtime-{fingerprint}.pem"
        if not target.exists():
            target.write_bytes(public_pem)

    @staticmethod
    def _fingerprint_bytes(public_pem: bytes) -> str:
        return hashlib.sha256(public_pem).hexdigest()

    def _load_or_create_encryption_key(self) -> bytes:
        if self.encryption_key_path.exists():
            data = self.encryption_key_path.read_bytes()
            if len(data) == 32:
                return data
        key = AESGCM.generate_key(bit_length=256)
        _write_secret(self.encryption_key_path, key)
        return key

    def _load_or_create_token(self, path: Path, env_name: str) -> str:
        if path.exists():
            return path.read_text().strip()
        token = secrets.token_urlsafe(32)
        _write_secret(path, token.encode())
        return token

    # ---- signatures -----------------------------------------------------
    def public_pem(self) -> bytes:
        return self._signing_key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )

    def key_id(self) -> str:
        return self._fingerprint_bytes(self.public_pem())[:16]

    def trusted_public_keys(self) -> list[tuple[str, Ed25519PublicKey]]:
        keys: list[tuple[str, Ed25519PublicKey]] = []
        for directory in (self.bundled_keys_dir, self.runtime_keys_dir):
            if not directory.exists():
                continue
            for path in sorted(directory.glob("*.pem")):
                try:
                    key = serialization.load_pem_public_key(path.read_bytes())
                    if isinstance(key, Ed25519PublicKey):
                        keys.append((path.stem, key))
                except (ValueError, TypeError):
                    continue
        # Always trust the currently active local key.
        keys.append(("active-" + self.key_id(), self._signing_key.public_key()))
        return keys

    def sign(self, payload: bytes) -> str:
        return base64.b64encode(self._signing_key.sign(payload)).decode("ascii")

    def verify(self, payload: bytes, signature_b64: str) -> tuple[bool, str]:
        try:
            signature = base64.b64decode(signature_b64, validate=True)
        except (ValueError, TypeError):
            return False, "malformed-signature"
        for key_id, key in self.trusted_public_keys():
            try:
                key.verify(signature, payload)
                return True, key_id
            except InvalidSignature:
                continue
        return False, "no-trusted-key"

    # ---- authenticated encryption ---------------------------------------
    def encrypt_text(self, plaintext: str) -> str:
        if not plaintext:
            return ""
        nonce = secrets.token_bytes(12)
        ciphertext = AESGCM(self._encryption_key).encrypt(
            nonce, plaintext.encode("utf-8"), b"aether-field-v1"
        )
        return "v1:" + base64.b64encode(nonce + ciphertext).decode("ascii")

    def decrypt_text(self, blob: str) -> str:
        if not blob:
            return ""
        if not blob.startswith("v1:"):
            return "[unreadable-encrypted-field]"
        try:
            raw = base64.b64decode(blob[3:], validate=True)
            return AESGCM(self._encryption_key).decrypt(
                raw[:12], raw[12:], b"aether-field-v1"
            ).decode("utf-8")
        except Exception:
            return "[unreadable-encrypted-field]"
