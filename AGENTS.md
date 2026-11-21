# Artemis working instructions

## Daily progress log

- Read `log/README.md` and the latest dated entry when starting work. Also read today's entry if it is a different file.
- On each day that work progresses, create or update `log/YYYY-MM-DD.md`. Find the current date and use the user's timezone, currently `America/Los_Angeles`; do not use the UTC date without converting it.
- Record completed work and findings, lessons learned, problems and their fixes, verification evidence, and tomorrow's tasks. Update the existing entry as work progresses; preserve earlier facts and clearly correct superseded conclusions.
- Ask the user what tomorrow's tasks should be. Do not invent priorities or silently carry forward a proposed task as agreed. While waiting, mark the section as awaiting the user's answer, then update it when they reply.
- Create entries on days work actually occurs; no unattended daily job is configured.

## Demo organization

- Keep demo launchers, example programs, report tooling and the coverage manifest under `demo/`.
- Use Gradle for builds and packaging. Keep generated demo databases, raw measurements and reports under ignored `build/` directories.
- Update affected launch paths and documentation when reorganizing demo assets. Keep old progress logs and generated run evidence as historical records.
