# Release notes

One file per release, named after its tag (`v1.47.1-own.4.md`).
Write it and commit it before pushing the tag: the release workflow
puts it at the top of the GitHub release, followed by the list of commits
since the previous tag. Without a file, the release only gets the commit list.

The tag must look like `v<upstream version>-own.<N>`. The version code of the
build is derived from it (upstream version code × 1000 + 100 + N), so N must
grow with each release of the same upstream version and stay below 900.

The commit list starts at the previous `-own.` tag reachable from the tagged
commit. After rebasing the fork onto a newer upstream, the old tags are no
longer ancestors, so the first release after a rebase may list no commits or
too many. The written notes above the list are unaffected.
