#!/usr/bin/env python3
"""Release B capability services only after a digest-bound successful migration."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time


HERE = Path(__file__).resolve().parent
PROJECT = "zhongshu-platform-b"
TARGET = f"{PROJECT}/capabilities"


def load_verify():
    spec = importlib.util.spec_from_file_location("capability_verify", HERE / "verify-capability-deployment.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VERIFY = load_verify()
demand = VERIFY.demand


def run(command, context, environment, timeout=1800):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment,
                            timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(f"{context}: exit {result.returncode}; inspect scoped B service logs")
    return result.stdout


def validate_candidate_container(container, service, expected_reference, expected_image_id):
    labels = container.get("Config", {}).get("Labels", {})
    demand(labels.get("com.docker.compose.project") == PROJECT
           and labels.get("com.docker.compose.service") == service,
           "Candidate container is outside the exact B project/service")
    demand(container.get("Config", {}).get("Image") == expected_reference,
           "Candidate container image reference differs from reviewed digest")
    demand(container.get("Image") == expected_image_id,
           "Candidate container actual image ID differs from the inspected digest")


def image_id(reference, environment):
    demand(VERIFY.immutable(reference), "Release image must use @sha256 digest")
    image = json.loads(run(["docker", "image", "inspect", reference], "Inspect preloaded candidate image",
                           environment, 60))[0]
    demand(reference in (image.get("RepoDigests") or []), "Local candidate lacks the exact reviewed RepoDigest")
    demand(str(image.get("Id", "")).startswith("sha256:"), "Candidate image ID is missing")
    return image["Id"]


def service_container(base, service, environment):
    ids = run(base + ["ps", "--all", "--quiet", service], f"Locate {service}", environment, 60).decode().split()
    demand(len(ids) == 1, f"Exactly one {service} container is required")
    return json.loads(run(["docker", "inspect", ids[0]], f"Inspect {service}", environment, 60))[0]


def require_data_healthy(base, environment):
    for service in ("postgres-b", "redis-b", "postgres-design", "redis-design"):
        container = service_container(base, service, environment)
        demand(container.get("State", {}).get("Running") is True
               and container.get("State", {}).get("Health", {}).get("Status") == "healthy",
               f"{service} must already be running and healthy")


def require_writers_stopped(base, environment):
    for service in ("api", "design-provider", "ai-runtime"):
        ids = run(base + ["ps", "--all", "--quiet", service], f"Inspect stopped {service}",
                  environment, 60).decode().split()
        if ids:
            demand(len(ids) == 1, f"Duplicate {service} containers refused")
            container = json.loads(run(["docker", "inspect", ids[0]], f"Inspect stopped {service}", environment, 60))[0]
            demand(not container.get("State", {}).get("Running"), f"Stop {service} before candidate migration")


def compose_base(env_file, values):
    return ["docker", "compose", "--project-name", PROJECT, "--env-file", str(env_file),
            *VERIFY.compose_files(values)]


def wait_healthy(container_id, service, environment, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        container = json.loads(run(["docker", "inspect", container_id], f"Wait for {service}", environment, 60))[0]
        state = container.get("State", {})
        if state.get("Running") and state.get("Health", {}).get("Status") == "healthy":
            return container
        demand(state.get("Running") is not False, f"{service} exited before becoming healthy")
        time.sleep(2)
    raise RuntimeError(f"{service} did not become healthy before the release timeout")


def start_writers(base, expected, environment):
    attempted = []
    try:
        for service in ("ai-runtime", "design-provider"):
            attempted.append(service)  # up can create/start a container before returning an error.
            run(base + ["up", "--detach", "--no-deps", "--force-recreate", "--no-build", "--pull", "never", service],
                f"Start digest-bound {service}", environment, 600)
            container = service_container(base, service, environment)
            validate_candidate_container(container, service, *expected[service])
            wait_healthy(container["Id"], service, environment)
        run(base + ["exec", "-T", "ai-runtime", "node", "/etc/platform/runtime-hmac-smoke.mjs"],
            "Runtime-to-Provider TLS/HMAC smoke", environment, 30)
        run(base + ["exec", "-T", "design-provider", "java", "-cp", "/opt/design-provider/smoke", "HmacSmoke",
                    "http://ai-runtime:8081", "/run/secrets/design-provider.properties"],
            "Provider-to-Runtime HMAC smoke", environment, 30)
        run(base + ["run", "--rm", "--no-deps", "--pull", "never", "--entrypoint", "java", "api",
                    "-cp", "/opt/platform/smoke", "CapabilitySmoke", "https://design-provider:48443",
                    "/run/secrets/platform.properties"],
            "Ephemeral API-image-to-Provider TLS/HMAC smoke", environment, 60)
        service = "api"
        attempted.append(service)
        run(base + ["up", "--detach", "--no-deps", "--force-recreate", "--no-build", "--pull", "never", service],
            "Start digest-bound api after capability smoke", environment, 600)
        container = service_container(base, service, environment)
        validate_candidate_container(container, service, *expected[service])
        wait_healthy(container["Id"], service, environment)
    except Exception as original:
        try:
            run(base + ["stop", "--timeout", "120", *attempted], "Stop every attempted B writer after release failure",
                environment, 180)
            require_writers_stopped(base, environment)
        except Exception as cleanup:
            raise RuntimeError(f"release failed: {original}; cleanup failed: {cleanup}") from original
        raise


def release(args):
    VERIFY.validate(args.env_file, False)
    values, environment = VERIFY.selected_env(args.env_file)
    base = compose_base(args.env_file, values)
    model = json.loads(run(base + ["config", "--format", "json"], "Render reviewed B release", environment, 60))
    VERIFY.same_host.inspect_existing_model_volumes(model, environment)
    expected = {service: (model["services"][service]["image"], image_id(model["services"][service]["image"], environment))
                for service in ("api", "design-provider", "design-provider-migrate", "ai-runtime")}
    demand(expected["design-provider"] == expected["design-provider-migrate"],
           "Migration and Provider must use the same candidate digest and image ID")
    require_data_healthy(base, environment)
    require_writers_stopped(base, environment)

    run(base + ["rm", "--force", "--stop", "design-provider-migrate"], "Remove prior B migration container",
        environment, 120)
    run(base + ["create", "--no-build", "--pull", "never", "--no-deps", "design-provider-migrate"],
        "Create digest-bound B migration", environment, 300)
    migration = service_container(base, "design-provider-migrate", environment)
    validate_candidate_container(migration, "design-provider-migrate", *expected["design-provider-migrate"])
    run(["docker", "start", "--attach", migration["Id"]], "Run candidate Provider migration", environment)
    migration = json.loads(run(["docker", "inspect", migration["Id"]], "Verify candidate migration result",
                               environment, 60))[0]
    validate_candidate_container(migration, "design-provider-migrate", *expected["design-provider-migrate"])
    demand(not migration["State"]["Running"] and migration["State"]["ExitCode"] == 0,
           "Candidate Provider migration did not exit successfully")

    start_writers(base, expected, environment)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-target")
    args = parser.parse_args()
    demand(args.env_file.is_absolute() and args.env_file.is_file(), "Use an absolute external deployment env file")
    demand(args.execute and args.confirm_target == TARGET,
           f"Explicit --execute --confirm-target {TARGET} is required")
    release(args)
    print("Digest-bound B migration and capability release completed")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, KeyError, json.JSONDecodeError) as error:
        print(f"B capability release refused/failed: {error}", file=sys.stderr)
        raise SystemExit(1)
