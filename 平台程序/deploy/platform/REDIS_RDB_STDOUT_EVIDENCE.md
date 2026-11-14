# Redis ACL stdout RDB isolation evidence

Date: 2026-11-14

This local test used the deployment-pinned image
`redis@sha256:44960b3100c48159a66d28c18b0588c193492e25f7551d60e51dd464fc3eca5b` in an
ephemeral container with no published port and persistence disabled. The default user was disabled. The
`platform_backup` user received only `+ping +replconf +psync +sync`, matching `redis-start.sh`; its attempt to run
`ACL GETUSER` returned `NOPERM`. The ephemeral password was not recorded.

Command under test:

```text
redis-cli --no-auth-warning --user platform_backup --rdb /dev/stdout > /tmp/locked-stdout.rdb
```

Observed evidence:

```text
SYNC sent to master, writing bytes of bulk transfer until EOF marker to '/dev/stdout'
Transfer finished with success after 172 bytes
172 /tmp/locked-stdout.rdb
850166aa4c200d391873e56af07925f873f6c915387f04f7ea24cda7393562d8  /tmp/locked-stdout.rdb
[offset 172] Checksum OK
[offset 172] \o/ RDB looks OK! \o/
```

`find /data -maxdepth 1 -name '*.rdb'` returned no entry. The container was removed after the check. This proves the
selected Redis CLI path for the pinned image and restricted backup ACL; it does not replace the isolated Linux full
backup/restore rehearsal.
