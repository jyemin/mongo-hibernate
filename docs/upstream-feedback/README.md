# Upstream feedback — surfaced by the MQLv2 elemMatch design

Phases 0-5 of the MQLv2 `$elemMatch` design (see `docs/superpowers/specs/2026-05-15-mqlv2-elemmatch-via-unnest-design.md`) confirmed several HQL surfaces are unbuildable due to limitations in Hibernate ORM itself, independent of the MongoDB extension. The MQLv2 server side evaluates the equivalent pipelines correctly (verified via mongosh); Hibernate just won't generate the SQL AST that compiles to them.

This directory collects:

- **`hibernate-bugs/`** — bug reports against Hibernate ORM, each with a minimal reproducer using a generic dialect (H2 or PostgreSQL) that the Hibernate team can run without our project.
- **`mqlv2-enhancements/`** — enhancement requests against MongoDB's MQLv2 for capabilities we'd want but don't have today. (May be empty if the server side covers everything we need.)

Each report is one self-contained markdown file. The format is:

```
# Title

**Affected version(s):** <list>
**Severity / type:** Bug | Enhancement
**Discovered by:** <our work>

## Summary
1-paragraph description.

## Minimal reproducer
Java entity + HQL + setup notes, using a non-Mongo dialect.

## Expected behavior
What should happen.

## Actual behavior
What does happen, including stack trace.

## Notes
Context, related issues, etc.
```
