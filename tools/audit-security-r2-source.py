#!/usr/bin/env python3
"""Source-level guard for Security r2 architectural boundaries.

Crypto correctness is covered by unit/server tests; this guard prevents high-impact regressions such
as turning installationId into authorization again, auto-registering every community launch, or
letting signed first-party headers follow redirects to a CDN.
"""
from pathlib import Path
import re, sys
R=Path(__file__).resolve().parents[1]
fail=[]
def text(rel): return (R/rel).read_text(encoding='utf-8')

device=text('app/src/main/kotlin/io/github/soulxyz/xyprt/data/remote/DeviceIdentity.kt')
api=text('app/src/main/kotlin/io/github/soulxyz/xyprt/data/remote/ServerApi.kt')
cc=text('app/src/main/kotlin/io/github/soulxyz/xyprt/data/remote/CoCreatorRepository.kt')
updates=text('app/src/main/kotlin/io/github/soulxyz/xyprt/data/UpdateRepository.kt')
assets=text('app/src/main/kotlin/io/github/soulxyz/xyprt/data/remote/RemoteAssetRepository.kt')
models=text('app/src/main/kotlin/io/github/soulxyz/xyprt/data/remote/EnhancedModelRepository.kt')

for n in ('XYPRT-DEVICE-AUTH-V1','XYPRT-DEVICE-CHALLENGE-V1','KeyProperties.KEY_ALGORITHM_EC','PURPOSE_SIGN'):
    if n not in device: fail.append('DeviceIdentity missing '+n)
for n in ('signedGet','signedPost','X-Device-Nonce','X-Device-Signature'):
    if n not in api: fail.append('ServerApi missing '+n)
if 'signedGet(path: String)' in api and 'instanceFollowRedirects = false' not in api:
    fail.append('signed JSON calls may follow redirects')
if re.search(r'init\s*\{\s*scope\.launch\s*\{\s*refresh',cc,re.S):
    fail.append('CoCreatorRepository eagerly binds DeviceAuth on ordinary app startup')
if 'api.postJson("/v1/app/update-check.php", body)' not in updates or 'api.signedPost("/v1/app/update-check.php", body)' not in updates:
    fail.append('update-check no longer has anonymous + signed split')
if 'val authenticateDevice = remote.encryptionMode != "none" || coCreator.state.value.active' not in assets:
    fail.append('asset public/protected auth split missing')
for n in ('/v1/models/list.php','/v1/models/lease.php'):
    if n not in models or 'signed' not in models: fail.append('protected model request guard missing')
for forbidden in ('SPONSOR_KEY','PREMIUM_TOKEN','SPONSOR_SECRET','GLOBAL_CONTENT_KEY'):
    if forbidden in (device+api+cc+assets+models): fail.append('forbidden reusable client secret marker: '+forbidden)

if fail:
    print('Security r2 source audit: FAIL')
    for x in fail: print(' -',x)
    sys.exit(1)
print('Security r2 source audit: PASS')
