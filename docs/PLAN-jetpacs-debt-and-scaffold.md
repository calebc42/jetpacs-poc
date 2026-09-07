# Foundation/scaffold debt — resolved milestone map

Status: historical. This path remains because the theme picker source/test and
the aggregate runner cite the original relocation decisions.

## Section 3: foundation relocations

The reusable results of that section are now owned by Jetpacs foundation:

- `emacs/jetpacs-org-settings.el` owns the generic Org/calendar settings
  sections and phone-generic seeding;
- `emacs/jetpacs-theme-picker.el` owns the provider-neutral theme picker, while
  concrete theme providers stay downstream; and
- `emacs/jetpacs-chrome.el` plus `jetpacs-apps.el` own scaffold and app chrome
  composition.

Current contracts and tests are documented in
[`CHROME-VOCABULARY.md`](CHROME-VOCABULARY.md). Open work belongs in
[`PLAN-poc3-rebuild.md`](PLAN-poc3-rebuild.md), not in this closed debt ledger.

Original ledger:

```sh
git show 6251e457aaadba8d2daf9ead3d305a7676412212:docs/PLAN-jetpacs-debt-and-scaffold.md
```
