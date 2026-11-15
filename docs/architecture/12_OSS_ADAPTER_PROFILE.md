# FS-200C — Native Alibaba OSS adapter profile

Owner authorized [#26](https://github.com/vyrnforge/file-storage/issues/26) after PR #59 merged at e567b2fc4df8ac4fcdcdde549c02cea3eaab9e71. Shared provider contract and DEC-FS012 apply. This is a bounded implementation, not a production OSS profile or completed legacy migration.

## Profile
- Native Alibaba SDK V1 library com.aliyun.oss:aliyun-sdk-oss:3.18.4 (Java 21), official [SDK guide](https://www.alibabacloud.com/help/en/oss/developer-reference/oss-java-sdk/). SDK confined to adapter module. Kernel 0.3.2 unchanged. Explicit V4 signature, region, trusted credentials provider, HTTPS target, no redirects, no auto retry, positive pool/connect/socket/request limits in OssClientFactory. PATH and VIRTUAL_HOST selected explicitly (AUTO maps to virtual host); CNAME rejected pending single-bucket binding and actual profile evidence. DNS/egress/endpoint allowlist and secret resolution belong to #27/#28; factory is not SSRF validation or authorization.
- Unversioned buckets only: getBucketVersioning must return a successful unversioned response (absent/empty Status or SDK OFF) before use; enabled, suspended and unknown states rejected. [GetBucketVersioning](https://help.aliyun.com/en/oss/developer-reference/getbucketversioning) documents absent Status. [PutObject](https://www.alibabacloud.com/help/en/oss/developer-reference/putobject) states forbid-overwrite is ignored for enabled/suspended buckets. No out-of-band versioning changes allowed; validator/profile evidence required. Native x-oss-forbid-overwrite:true, exact content length prevalidated with bounded private spool, no HEAD-then-PUT. No optional capabilities; multipart/copy/presign deferred. SHA256 null, no ETag/CRC substitution. Exact version requests unsupported. Same backend per instance, nonoverlapping namespace within bucket; cross-instance registry checks later.
- Fixed SDK connection/socket/request limits plus bounded no-queue executor for caller remaining timeout. Timed-out SDK work may still complete; no automatic retry; late GET closes response, PUT spool and permit remain owned by request until completion. Caller must provide bounded/cancellable input; arbitrary blocking InputStream cannot be safely forced to return. Download deadline watchdog uses OSSObject.forcedClose; caller closes early. No DB transaction across bytes. Bounded cursor TTL/entries, instance-local opaque token and exact scope. Neutral failures only; SDK internal logging and real transport stall/CRC/credential permissions remain release verification.
- Local test fixture uses native SDK V4 HTTP requests against loopback only, synthetic credentials, path addressing, two buckets, XML responses, conditional header and atomic fixture map. Fixture models required behavior; not a real OSS server, not evidence of provider-side atomicity, RAM permissions, TLS, CNAME or cloud signing acceptance. CRC disabled only in fixture; production factory enables CRC. No cloud account/credentials requested or used. 1 MiB fixture cap, four staging permits, eight request workers; not production defaults.

## Legacy parity and gates
Sanitized #14 inventory confirms legacy OSS exact key, forbid-overwrite, CNAME/path/virtual host, policy signing, head/list/delete and browser direct upload. Preserve exact keys and native behavior; private gateway replaces public direct signing per DEC-FS012. CNAME and optional signing/multipart remain explicitly unsupported in initial profile, not silently emulated. Full private legacy build/regression and migration data still unavailable; #14 stays open. Real OSS isolated staging must run inherited ProviderContract and profile-specific permission, versioning, concurrency, TLS/region/addressing, CRC, timeout/cleanup tests before #26 acceptance; obtain owner-approved staging target and secure credential reference, not secrets in repository/chat. No native OSS compatibility claim from loopback alone.

## Acceptance
O01 shared ProviderContract against actual native SDK loopback: exact bytes/size/zero, conditional overwrite/concurrency, list cursor scope, missing/delete/version/optional behavior.
O02 native V4 header, forbidden overwrite and content length observed on wire; versioning/permission errors and timeout neutral; stream expiry, spool cleanup, namespace checks.
O03 actual staging evidence and #14 legacy parity; pending. No deployment or later work authorized.


## Explicit staging acceptance (not executed)
`OssStagingIT` reuses the same contract against actual OSS via the production client factory. It is deliberately outside default Surefire discovery and must be selected explicitly; no skipped-test claim. Use only two dedicated disposable, never-versioned buckets with approved private TLS endpoint, region and short-lived STS credentials limited to the test prefix and required bucket versioning/list permissions. No bucket creation/deletion or production data. Run locally in an approved secret-injection environment, not by adding credentials to public CI or chat.

```sh
# Inject the following variables securely outside source control:
# FS_OSS_STAGING_ENDPOINT, FS_OSS_STAGING_REGION
# FS_OSS_STAGING_BUCKET_A, FS_OSS_STAGING_BUCKET_B
# FS_OSS_STAGING_ACCESS_KEY_ID, FS_OSS_STAGING_ACCESS_KEY_SECRET
# FS_OSS_STAGING_SECURITY_TOKEN
export FS_OSS_STAGING_CONFIRM=DEDICATED_DISPOSABLE_OSS_BUCKETS
mvn -f backend/pom.xml -pl file-storage-provider-oss -am verify \
    -Dtest=OssStagingIT -Dsurefire.failIfNoSpecifiedTests=false
```

The suite writes only unique synthetic contract prefixes and deletes its own test objects. Inspect and reconcile residual objects after any interrupted test. Record sanitized results and exact revision; additionally verify actual target-specific RAM denial, TLS/egress, CRC, stream stalls, versioning constraints, logging/credential hygiene, optional capability matrix and any legacy CNAME requirement. No staging credentials or actual execution available in this increment.

CI 43676179408 compiled native SDK code but failed test admission because the SDK returned absent Status for the empty unversioned configuration. Corrected to accept the documented successful empty response, reject enabled/suspended/unknown. No tests disabled.
