#!/usr/bin/env python3
"""Fail-closed validation for the B capability Compose overlay; never starts containers."""
import argparse
import base64
import binascii
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import same_host_storage as same_host
from runtime_storage_proof import validate_runtime_proof
from ops import validate_asset_spool

HERE = Path(__file__).resolve().parent
REPO = next(parent for parent in HERE.parents if (parent / ".git").exists())
EXPECTED = {"api", "postgres-b", "redis-b", "design-provider-migrate", "design-provider",
            "ai-runtime", "postgres-design", "redis-design"}
EXTERNAL_FILES = (
    "PLATFORM_CONFIG_FILE", "DESIGN_PROVIDER_CONFIG_FILE", "DESIGN_PROVIDER_MIGRATION_CONFIG_FILE",
    "DESIGN_PROVIDER_KEYSTORE_FILE", "DESIGN_PROVIDER_TRUSTSTORE_FILE", "DESIGN_PROVIDER_CA_FILE",
    "AI_RUNTIME_CONFIG_FILE", "DESIGN_PG_BOOTSTRAP_PASSWORD_FILE", "DESIGN_PG_OWNER_PASSWORD_FILE",
    "DESIGN_PG_APP_PASSWORD_FILE", "DESIGN_REDIS_APP_PASSWORD_FILE", "DESIGN_REDIS_BACKUP_PASSWORD_FILE",
)
AUDITED_B_COS_BUCKET = "zhongshu-platform-b-assets-1397776231"
AUDITED_B_COS_REGION = "ap-shanghai"
AUDITED_B_COS_ENDPOINT = f"https://cos.{AUDITED_B_COS_REGION}.myqcloud.com"
AUDITED_B_COS_OBJECT_ORIGIN = (
    f"https://{AUDITED_B_COS_BUCKET}.cos.{AUDITED_B_COS_REGION}.myqcloud.com"
)

REQUIRED_CAPABILITY_OPERATIONS = frozenset(
    """
    CREATE_PROJECT LIST_PROJECTS GET_PROJECT GET_AI_JOB CANCEL_AI_JOB NOTE_POLISH
    LIST_RESULT_VERSIONS CREATE_FLAT_JOB CREATE_ELEVATION_JOB CONFIRM_FLAT_CANDIDATE
    SELECT_ELEVATION_CANDIDATE CREATE_REVISION_REQUEST VALIDATE_SCHEME_REF BUDGET_RULE_INFO
    CREATE_BUDGET_ESTIMATE CREATE_UPLOAD_TICKET COMPLETE_UPLOAD CREATE_DOWNLOAD_URL QUERY_RECEIPT
    GET_SETTLEMENT
    """.split()
)


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def run(command, environment):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, check=False,
                            env=environment)
    demand(result.returncode == 0, "Compose model could not be rendered; inspect only the external deployment inputs")
    return result.stdout


def immutable(image):
    return re.fullmatch(r"[^@]+@sha256:[0-9a-f]{64}", image) is not None


def parse_env(path):
    values = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        demand(sep and re.fullmatch(r"[A-Z][A-Z0-9_]*", key) and key not in values,
               "Env file must use unique simple KEY=value lines")
        values[key] = value.strip().strip("'\"")
    return values


def parse_properties(path):
    values = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        demand(separator and key.strip() and key.strip() not in values, "Properties must use unique key=value lines")
        values[key.strip()] = value.strip()
    return values


def same_secret(left, right):
    return bool(left and right) and hmac.compare_digest(left.encode(), right.encode())


def configured(values, key):
    """Reject template placeholders; this is presence validation, never live authentication."""
    value = values.get(key, "").strip()
    upper = value.upper()
    demand(bool(value) and not any(marker in upper for marker in
           ("REPLACE", "PLACEHOLDER", "CHANGEME", "${", "<", ">"))
           and upper not in ("REDACTED", "TODO", "TBD"),
           f"Required non-placeholder configuration is missing: {key}")
    return value


def https_configuration(values, key, origin_only=False):
    value = configured(values, key)
    parsed = urlsplit(value)
    demand(parsed.scheme == "https" and parsed.hostname and parsed.username is None
           and parsed.password is None and not parsed.query and not parsed.fragment
           and (not origin_only or not parsed.path.strip("/")),
           f"Configuration must be a credential-free HTTPS {'origin' if origin_only else 'URL'}: {key}")
    return value


def validate_production_channels(platform, provider, runtime):
    # These channels are part of the approved full B release, not integration fixtures.
    for key, expected in (("platform.identity.wechat.provider", "real"),
                          ("platform.identity.sms.provider", "real"),
                          ("zhongshu.content.provider", "real"),
                          ("zhongshu.content.task-sweep-enabled", "true")):
        demand(platform.get(key) == expected, f"Full production release requires {key}={expected}")
    for key in ("platform.identity.wechat.apps[0].appid", "platform.identity.wechat.apps[0].secret",
                "platform.identity.sms.sdk-app-id", "platform.identity.sms.sign-name",
                "platform.identity.sms.template-id", "platform.identity.sms.secret-id",
                "platform.identity.sms.secret-key", "platform.identity.sms.region",
                "zhongshu.content.diadi.app-id", "zhongshu.content.diadi.app-secret",
                "zhongshu.content.dashscope.api-key", "zhongshu.content.deepseek.api-key",
                "zhongshu.content.hifly.api-key", "zhongshu.content.metaso.api-key"):
        configured(platform, key)
    demand(provider.get("zhongshu.design.asset.moderation.provider") == "real",
           "Production design moderation must be real")
    demand(provider.get("zhongshu.design.asset.moderation.daily-limit") == "0",
           "Production design moderation daily-limit must explicitly be 0")
    for suffix in ("secret-id", "secret-key", "region", "biz-type"):
        configured(provider, "zhongshu.design.asset.moderation." + suffix)
    for key in ("ZS_AI_API_KEY", "ZS_AI_MODEL", "ZS_AI_IMAGE_MODEL"):
        configured(runtime, key)
    if "ZS_AI_IMAGE_MODEL_FALLBACK" in runtime:
        configured(runtime, "ZS_AI_IMAGE_MODEL_FALLBACK")
    https_configuration(runtime, "ZS_AI_BASE_URL")
    origins = configured(runtime, "ZS_AI_IMAGE_URL_ORIGINS").split(",")
    for origin in origins:
        https_configuration({"origin": origin.strip()}, "origin", origin_only=True)


def validate_configuration_contract(platform, provider, runtime, expected_bucket):
    demand(expected_bucket == AUDITED_B_COS_BUCKET,
           "COS_B_BUCKET must name the audited B asset bucket")
    demand(platform.get("zhongshu.franchise.asset.storage.provider") == "cos",
           "API asset storage must be COS in production")
    demand(platform.get("zhongshu.franchise.asset.storage.cos.bucket") == expected_bucket,
           "API COS bucket differs from the backup B bucket")
    demand(platform.get("zhongshu.franchise.asset.storage.cos.region") == AUDITED_B_COS_REGION,
           "API COS region differs from the audited B region")
    demand(platform.get("zhongshu.franchise.asset.storage.cos.endpoint") == AUDITED_B_COS_ENDPOINT,
           "API COS endpoint differs from the audited regional SDK endpoint")
    demand(platform.get("zhongshu.franchise.asset.storage.cos.path-style-access") == "false",
           "API COS path-style access must be explicitly disabled")
    demand(provider.get("zhongshu.design.asset.storage.provider") == "cos",
           "Provider storage must be COS in production")
    demand(provider.get("zhongshu.design.asset.storage.cos.bucket") == expected_bucket,
           "Provider COS bucket differs from the backup B bucket")
    demand(provider.get("zhongshu.design.asset.storage.cos.region") == AUDITED_B_COS_REGION,
           "Provider COS region differs from the audited B region")
    demand(provider.get("zhongshu.design.asset.storage.cos.path-style-access") == "false",
           "Provider COS path-style access must be explicitly disabled")
    endpoint = provider.get("zhongshu.design.asset.storage.cos.endpoint", "")
    parsed = urlsplit(endpoint)
    demand(parsed.scheme == "https" and parsed.netloc and not parsed.path.strip("/")
           and not parsed.query and not parsed.fragment, "Provider COS endpoint must be a fixed HTTPS origin")
    demand(endpoint == AUDITED_B_COS_ENDPOINT
           and endpoint == platform.get("zhongshu.franchise.asset.storage.cos.endpoint"),
           "API and Provider COS endpoints must equal the audited regional SDK endpoint")
    demand(runtime.get("ZS_AI_STORAGE_MODE") == "https"
           and runtime.get("ZS_AI_STORAGE_ORIGINS") == AUDITED_B_COS_OBJECT_ORIGIN,
           "Runtime storage origin must equal the audited B bucket virtual-host origin")
    reference_host = platform.get("zhongshu.design.remote.reference-upload-hosts", "").strip()
    demand(reference_host == urlsplit(AUDITED_B_COS_OBJECT_ORIGIN).hostname,
           "API reference upload host must equal the audited B bucket host")
    for values, prefix in ((platform, "zhongshu.franchise.asset.storage.cos"),
                           (provider, "zhongshu.design.asset.storage.cos")):
        configured(values, prefix + ".secret-id")
        configured(values, prefix + ".secret-key")
    demand(not same_secret(platform["zhongshu.franchise.asset.storage.cos.secret-id"],
                           provider["zhongshu.design.asset.storage.cos.secret-id"])
           and not same_secret(platform["zhongshu.franchise.asset.storage.cos.secret-key"],
                               provider["zhongshu.design.asset.storage.cos.secret-key"]),
           "API and Provider COS must use distinct identities and credentials")
    demand(platform.get("zhongshu.design.remote.enabled") == "true"
           and provider.get("zhongshu.design.capability.enabled") == "true",
           "API remote design and Provider capability must be explicitly enabled")
    validate_production_channels(platform, provider, runtime)
    shared = provider.get("zhongshu.ai.internal-secret", "")
    demand(len(shared) >= 32 and same_secret(shared, runtime.get("ZS_INTERNAL_SECRET", "")),
           "Provider and Runtime shared HMAC secrets differ")
    active = platform.get("zhongshu.design.remote.active-key-id", "")
    caller = platform.get("zhongshu.design.remote.caller-app-id", "")
    demand(re.fullmatch(r"[a-zA-Z0-9._-]{1,64}", active) and caller,
           "API active capability key and caller are required")
    operation_text = provider.get(f"zhongshu.design.capability.keys.{active}.operations", "")
    operations = [operation.strip() for operation in operation_text.split(",")]
    demand(set(operations) == REQUIRED_CAPABILITY_OPERATIONS and len(operations) == len(set(operations)),
           "Provider capability operations must exactly match the audited B operation set")
    api_key = platform.get(f"zhongshu.design.remote.keys.{active}", "")
    prefix = f"zhongshu.design.capability.keys.{active}"
    demand(provider.get(prefix + ".caller-app-id") == caller
           and same_secret(api_key, provider.get(prefix + ".secret-base64", "")),
           "API caller/key does not match the Provider capability key")
    try:
        decoded = base64.b64decode(api_key, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("API capability key is not strict Base64") from error
    demand(len(decoded) >= 32, "API capability HMAC key must decode to at least 32 bytes")
    challenge = b"zhongshu-b-deployment-contract-smoke"
    demand(hmac.compare_digest(hmac.digest(decoded, challenge, "sha256"),
                               hmac.digest(base64.b64decode(provider[prefix + ".secret-base64"], validate=True),
                                           challenge, "sha256")),
           "API/Provider HMAC calculation mismatch")
    demand(hmac.compare_digest(hmac.digest(shared.encode(), challenge, "sha256"),
                               hmac.digest(runtime["ZS_INTERNAL_SECRET"].encode(), challenge, "sha256")),
           "Provider/Runtime HMAC calculation mismatch")


def secret_command(command, stdin, context):
    result = subprocess.run(command, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=30, check=False)
    demand(result.returncode == 0, f"{context} failed")
    return result.stdout


def pem_fingerprints(value):
    certificates = re.findall(rb"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", value, re.S)
    demand(certificates, "Certificate bundle is empty")
    return {hashlib.sha256(ssl.PEM_cert_to_DER_cert(item.decode())).hexdigest() for item in certificates}


def validate_tls_contract(provider, keystore, truststore, ca_file):
    password = provider.get("server.ssl.key-store-password", "")
    demand(password and provider.get("server.ssl.key-alias") == "design-provider",
           "Provider TLS keystore password/alias contract is incomplete")
    leaf_bundle = secret_command(["openssl", "pkcs12", "-in", str(keystore), "-clcerts", "-nokeys",
                                  "-passin", "stdin"], (password + "\n").encode(), "Provider keystore certificate check")
    private_key = secret_command(["openssl", "pkcs12", "-in", str(keystore), "-nocerts", "-nodes",
                                  "-passin", "stdin"], (password + "\n").encode(), "Provider keystore private-key check")
    demand(b"PRIVATE KEY" in private_key and b"friendlyName: design-provider" in leaf_bundle,
           "Provider keystore lacks the design-provider private key/certificate alias")
    certificate_text = secret_command(["openssl", "x509", "-text", "-noout"], leaf_bundle,
                                      "Provider certificate SAN check")
    demand(re.search(rb"Subject Alternative Name:.*?DNS:design-provider(?:\s|,|$)", certificate_text, re.S),
           "Provider certificate SAN does not contain design-provider")
    trust_bundle = secret_command(["openssl", "pkcs12", "-in", str(truststore), "-nokeys",
                                   "-passin", "stdin"], b"changeit\n", "Provider truststore check")
    trust_keys = secret_command(["openssl", "pkcs12", "-in", str(truststore), "-nocerts", "-nodes",
                                 "-passin", "stdin"], b"changeit\n", "Provider truststore key check")
    demand(b"PRIVATE KEY" not in trust_keys, "Provider truststore must not contain private keys")
    ca_bytes = ca_file.read_bytes()
    demand(pem_fingerprints(ca_bytes) <= pem_fingerprints(trust_bundle),
           "Provider CA PEM is not present in the API/Provider truststore")
    with tempfile.NamedTemporaryFile("wb") as leaf:
        leaf.write(leaf_bundle)
        leaf.flush()
        secret_command(["openssl", "verify", "-CAfile", str(ca_file), leaf.name], b"",
                       "Provider certificate chain check")


def selected_env(path, ambient=None):
    ambient = os.environ if ambient is None else ambient
    values = parse_env(path)
    for key, value in values.items():
        demand(key not in ambient or ambient[key] == value,
               f"Selected env file conflicts with ambient variable: {key}")
    demand(not any(key.startswith("COMPOSE_") for key in ambient),
           "Ambient COMPOSE_* variables are refused")
    environment = {key: ambient[key] for key in ("PATH", "HOME", "TMPDIR", "DOCKER_HOST", "DOCKER_CONTEXT",
                                                  "DOCKER_CONFIG") if key in ambient}
    environment.update(values)
    return values, environment


def compose_files(values):
    return ["-f", str(HERE / "compose.yaml"), "-f", str(HERE / "compose.capabilities.yaml"),
            *same_host.compose_overlay(values)]


def validate(env_file, structure_only):
    values, environment = selected_env(env_file)
    command = ["docker", "compose", "--project-name", "zhongshu-platform-b", "--env-file", str(env_file),
               *compose_files(values), "config", "--format", "json"]
    model = json.loads(run(command, environment))
    services = model.get("services", {})
    same_host.validate_compose_volumes(model, values)
    demand(set(services) == EXPECTED, "Unexpected full B service set")
    demand(set(model.get("networks", {})) == {"private"}, "Capability services must use only the B private network")
    for name, service in services.items():
        demand(immutable(service.get("image", "")), f"{name} requires an immutable image")
        demand(not service.get("build") and service.get("pull_policy") == "never", f"{name} build/pull is forbidden")
        demand(set(service.get("networks", {})) == {"private"}, f"{name} escaped the B private network")
        if name != "api":
            demand(not service.get("ports"), f"{name} must not publish a host port")
    api_env = services["api"].get("environment", {})
    validate_asset_spool(services["api"])
    demand(api_env.get("PF_DESIGN_ORIGIN") == "https://design-provider:48443", "B API must call the B Provider over TLS")
    public_origin = api_env.get("PF_DESIGN_PUBLIC_CASES_ORIGIN", "")
    demand(public_origin.startswith("https://") and "design-provider" not in public_origin,
           "A public-case origin must be a separate fixed HTTPS origin")
    runtime = services["ai-runtime"]
    mounts = {item["target"]: item for item in runtime.get("volumes", [])}
    demand(mounts.get("/data", {}).get("type") == "bind", "Runtime journal must be a host bind, not a named volume")
    demand(runtime.get("environment", {}).get("ZS_AI_CORE_URL") == "https://design-provider:48443",
           "Runtime must call the B Provider")
    demand(services["design-provider"].get("environment", {}).get("HEALTH_URL") ==
           "https://design-provider:48443/actuator/health/readiness",
           "Provider readiness must use the certificate SAN contract")
    demand(services["design-provider"].get("depends_on", {}).get("ai-runtime", {}).get("condition") == "service_healthy",
           "Provider readiness requires Runtime receipt availability")
    provider_command = set(services["design-provider"].get("command", []))
    demand("--spring.main.lazy-initialization=false" in provider_command
           and "--spring.flyway.enabled=false" in provider_command,
           "Provider must lock eager initialization and disable automatic Flyway")
    if structure_only:
        return
    same_host.inspect_existing_model_volumes(model, environment)
    storage_contract = None
    identity_mode = values.get("B_SAME_HOST_STORAGE_IDENTITY_MODE", "serial-wwn-thick-v1")
    if same_host.compose_overlay(values):
        if identity_mode == "tencent-cbs-v1":
            storage_contract = same_host.validated_cbs_data_contract(values)
        else:
            same_host.validate_host(values)
    repository = REPO.resolve()
    for key in EXTERNAL_FILES:
        path = Path(values.get(key, "")).resolve(strict=True)
        demand(not path.is_relative_to(repository), f"{key} must stay outside the repository")
        metadata = path.stat()
        demand(path.is_file() and not (metadata.st_mode & 0o007), f"{key} must not be world-accessible")
        if sys.platform.startswith("linux"):
            demand(metadata.st_gid == 20000 and metadata.st_mode & 0o040,
                   f"{key} must be readable by container GID 20000")
    platform_path = Path(values["PLATFORM_CONFIG_FILE"])
    platform_text = platform_path.read_text()
    demand(re.search(r"(?m)^zhongshu\.org-point\.headquarters-tenant-id=\d+$", platform_text),
           "Real headquarters tenant id is required in external platform config")
    demand(re.search(r"(?m)^zhongshu\.design\.remote\.origin=https://design-provider:48443$", platform_text),
           "External platform config must keep the B Provider origin fixed")
    runtime_values = parse_env(Path(values["AI_RUNTIME_CONFIG_FILE"]))
    demand("ZS_AI_CORE_URL" not in runtime_values and "ZS_AI_JOURNAL_DIR" not in runtime_values,
           "Runtime secret file cannot override fixed B service boundaries")
    proof_hash = runtime_values.get("ZS_AI_JOURNAL_FS_PROOF_SHA256", "")
    demand(re.fullmatch(r"[0-9a-f]{64}", proof_hash), "Runtime capacity proof hash is required")
    journal = Path(values.get("AI_RUNTIME_JOURNAL_DIR", "")).resolve(strict=True)
    demand(journal.is_dir(), "Runtime journal must be a directory")
    same_host.validate_journal(values, journal)
    if sys.platform.startswith("linux"):
        fs = subprocess.run(["findmnt", "-n", "-o", "FSTYPE", "--target", str(journal)],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=10, check=False)
        demand(fs.returncode == 0 and fs.stdout.strip() in ("ext4", "xfs"), "Runtime journal requires ext4/xfs")
    proof = journal / ".capacity-proof.json"
    demand(proof.is_file() and not proof.is_symlink(), "Runtime capacity proof is missing")
    demand(proof.stat().st_size <= 8192, "Runtime capacity proof is too large")
    validate_runtime_proof(proof.read_bytes(), proof_hash, identity_mode, storage_contract)
    provider_values = parse_properties(Path(values["DESIGN_PROVIDER_CONFIG_FILE"]))
    validate_configuration_contract(parse_properties(platform_path), provider_values, runtime_values,
                                    values.get("COS_B_BUCKET", ""))
    validate_tls_contract(provider_values, Path(values["DESIGN_PROVIDER_KEYSTORE_FILE"]),
                          Path(values["DESIGN_PROVIDER_TRUSTSTORE_FILE"]), Path(values["DESIGN_PROVIDER_CA_FILE"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--structure-only", action="store_true")
    args = parser.parse_args()
    demand(args.env_file.is_absolute() and args.env_file.is_file(), "Use an absolute external env file")
    validate(args.env_file, args.structure_only)
    print("B capability deployment validation passed")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError, KeyError, json.JSONDecodeError) as error:
        print(f"B capability deployment refused: {error}", file=sys.stderr)
        raise SystemExit(1)
