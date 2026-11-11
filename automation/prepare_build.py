"""Inject CI-only configuration; never print credentials or commit generated files."""
import base64
import json
import os
from pathlib import Path
import re
import sys


def replace_once(text, pattern, replacement):
    text, count = re.subn(pattern, lambda _: replacement, text)
    if count != 1:
        raise ValueError(f"Upstream configuration changed: {pattern}")
    return text


def main():
    root = Path(sys.argv[1]).resolve()
    required = ["TELEGRAM_API_ID", "TELEGRAM_API_HASH", "SIGNING_KEYSTORE_BASE64",
                "SIGNING_KEY_ALIAS", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_PASSWORD"]
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise ValueError("Missing GitHub Secrets: " + ", ".join(missing))
    api_id = os.environ["TELEGRAM_API_ID"]
    api_hash = os.environ["TELEGRAM_API_HASH"]
    package = os.environ.get("APP_PACKAGE") or "io.github.feiyang.messenger"
    passkeys = os.environ.get("ENABLE_PASSKEYS", "false").lower()
    if not re.fullmatch(r"[1-9][0-9]*", api_id) or not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash):
        raise ValueError("Invalid Telegram API credentials")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", package) or package.startswith("org.telegram."):
        raise ValueError("Choose a custom lowercase APP_PACKAGE")
    if passkeys not in ("true", "false"):
        raise ValueError("ENABLE_PASSKEYS must be true or false")
    java = root / "TMessagesProj/src/main/java/org/telegram/messenger"
    buildvars = java / "BuildVars.java"
    text = buildvars.read_text(encoding="utf-8")
    for field, value in [("APP_ID", api_id), ("APP_HASH", json.dumps(api_hash)), ("SUPPORTS_PASSKEYS", passkeys)]:
        text = replace_once(text, rf"public static (?:int|String|boolean) {field}\s*=\s*[^;]+;",
                            f"public static {'int' if field == 'APP_ID' else 'String' if field == 'APP_HASH' else 'boolean'} {field} = {value};")
    buildvars.write_text(text, encoding="utf-8")
    keystore = root / "TMessagesProj/config/release.keystore"
    keystore_data = base64.b64decode(os.environ["SIGNING_KEYSTORE_BASE64"], validate=True)
    store_type = "JKS" if keystore_data[:4] == bytes.fromhex("feedfeed") else "PKCS12"
    keystore.write_bytes(keystore_data)
    keystore.chmod(0o600)
    props = root / "gradle.properties"
    text = props.read_text(encoding="utf-8")
    text = replace_once(text, r"(?m)^APP_PACKAGE=.*$", "APP_PACKAGE=" + package)
    # Gradle reads passwords from environment properties, not files or command lines.
    text = re.sub(r"(?m)^RELEASE_(?:KEY_PASSWORD|KEY_ALIAS|STORE_PASSWORD)=.*\n?", "", text)
    props.write_text(text, encoding="utf-8")
    module = root / "TMessagesProj_AppStandalone"
    gradle = module / "build.gradle"
    text = gradle.read_text(encoding="utf-8")
    text, count = re.subn(r'(storeFile file\("\.\./TMessagesProj/config/release.keystore"\))',
                          lambda match: match[0] + '\n            storeType "' + store_type + '"', text)
    if count != 2:
        raise ValueError("Upstream signing configuration changed")
    text = replace_once(text, r'abiFilters "armeabi-v7a", "arm64-v8a", "x86", "x86_64"', 'abiFilters "arm64-v8a"')
    firebase = os.environ.get("FIREBASE_GOOGLE_SERVICES_BASE64", "")
    if firebase:
        data = base64.b64decode(firebase, validate=True)
        config = json.loads(data)
        packages = [c["client_info"]["android_client_info"]["package_name"] for c in config.get("client", [])]
        if package + ".web" not in packages:
            raise ValueError("Firebase config must match APP_PACKAGE + .web")
        (module / "google-services.json").write_bytes(data)
    else:
        text = replace_once(text, r"apply plugin: 'com.google.gms.google-services'", "// No custom Firebase configuration: FCM unavailable.")
        print("No Firebase secret: FCM push is unavailable; background delivery must be tested.")
    gradle.write_text(text, encoding="utf-8")
    print("Prepared standalone arm64 build for " + package + ".web; passkeys=" + passkeys)


if __name__ == "__main__":
    main()
