# Update server

The PHP update gateway is maintained in a separate Git repository:

`https://github.com/soulxyz/xyprt_update_server`

This Android repository intentionally contains no PHP server source. The App talks to the stable gateway API configured by `XYPRT_UPDATE_API_BASE_URL` (default: `https://api.xyprt.5am.top`).

`update.json` at repository root is retained only for compatibility with older App versions; current versions use the PHP gateway and GitHub Release metadata.
