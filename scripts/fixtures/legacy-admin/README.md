# Historical local admin fixtures

These eight modules preserve the retired local owner/finance model for historical
fixture replay. They are outside `project.config.json`'s `miniprogramRoot` and must
never be imported by a product page. No forwarding modules remain under
`miniprogram/pages/admin`.

The retired graph starts at `owner-business.js`, which uses `owner-model.js`,
`owner-home.js`, `owner-performance.js`, and `model.js`. The local model delegates
writes to `owner-settings.js`, `finance-actions.js`, and `finance-save.js`.
References to shared historical readers and helpers still resolve to
`miniprogram/utils`; `organization-actions-v2.js` remains at its existing path.
This move preserves the fixture behavior and does not change saved user data.

Scripts importing these fixtures can reproduce historical in-memory assertions
and inspect previously captured local records. Their success is **not evidence
that the current online owner, engineering, or finance workflow works**. In mixed
scripts, such as `verify-owner-three-level.cjs`, only the separately rendered API
page assertions exercise the current page. Native scripts and the dated
`engineering-finance-audit-20261021` report retain their original snapshot and
version checks and require their original snapshot for replay. Other retired
engineering sources are already absent from the current package. Their source
fingerprint checks remain strict; the fixture move does not refresh old evidence
into current acceptance or make a missing historical snapshot pass.

Current coverage uses the actual API pages in `verify-boss-home.cjs`,
`verify-api-workbench-contracts.cjs`, `verify-finance-project-readonly.cjs`,
`verify-finance-journey.cjs`, and the live integration checks. These still need the
normal paired backend and environment verification; local fixtures cannot replace
them.

`verify-legacy-admin-boundary.cjs`, included in `check:dependencies`, prevents the
retired files or an import of the fixtures from re-entering the miniapp package and
checks that each moved module's direct dependencies still resolve.
