#!/usr/bin/env bash
# Disposable MinIO test fixture from the official, checksum-pinned release.
# No registry image pull, no production credentials, no image publishing.
set -euo pipefail

release=RELEASE.2025-04-22T22-12-26Z
image=file-storage-test/minio:2025-04-22-verified
case "$(docker version --format '{{.Server.Arch}}')" in
    amd64|x86_64)
        arch=amd64
        digest=53e2a2cb16c5366ea6fbbc479c19ddb4c6a0948273e752f740fb1fbf27bb817c
        ;;
    arm64|aarch64)
        arch=arm64
        digest=6c2f3142c94240206123177f4ba1e360daa5d1e0a4962e90757ef4f92c3ab57c
        ;;
    *)
        echo "Unsupported Docker architecture for S3 test fixture" >&2
        exit 1
        ;;
esac

# The explicit CI preflight and make ci share the same fixture.
if [[ "$(docker image inspect --format '{{index .Config.Labels "io.vyrnforge.fixture.sha256"}}' "$image" 2>/dev/null || true)" == "$digest" ]]; then
    echo "Verified-release S3 fixture already prepared ($arch)"
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
url="https://github.com/minio/minio/releases/download/$release/minio.linux-$arch.$release"
echo "Downloading official MinIO release binary ($arch)"
curl --fail --location --silent --show-error --retry 2 --retry-max-time 150 \
    --connect-timeout 15 --max-time 120 "$url" --output "$work/minio"
printf '%s  %s\n' "$digest" "$work/minio" | sha256sum --check --status
chmod 0755 "$work/minio"
mkdir -p "$work/data" "$work/home" "$work/etc"
printf 'minio:x:10001:10001:MinIO test fixture:/home/minio:/sbin/nologin\n' > "$work/etc/passwd"
printf 'minio:x:10001:\n' > "$work/etc/group"
cat > "$work/Dockerfile" <<'DOCKERFILE'
FROM scratch
COPY minio /usr/bin/minio
COPY etc/passwd /etc/passwd
COPY etc/group /etc/group
COPY --chown=10001:10001 data/ /data/
COPY --chown=10001:10001 home/ /home/minio/
ENV HOME=/home/minio USER=minio TMPDIR=/home/minio MINIO_UPDATE=off MINIO_BROWSER=off
USER 10001:10001
EXPOSE 9000
ENTRYPOINT ["/usr/bin/minio"]
CMD ["server", "/data"]
DOCKERFILE

echo "Building isolated S3 fixture from verified binary; no base image or network"
docker build --network none --platform "linux/$arch" \
    --label "io.vyrnforge.fixture.sha256=$digest" \
    --tag "$image" "$work"
echo "S3 compatibility fixture prepared: $image"
