---
title: Software Design by Example in Clojure
lede: "A tool-based introduction to software design, one working program at a time."
---

The best way to learn design is to study examples, and the best examples
are the tools programmers use every day. This book builds small versions
of those tools in Clojure: a test runner, a glob matcher, an interpreter,
a file archiver, a build manager, a web server, a virtual machine, a
debugger, and more. Each one is a few hundred lines long, each one works,
and each one teaches a handful of ideas about how to structure software so
that it is easy to understand, test, and change.

This is a port of Greg Wilson's
[*Software Design by Example: A Tool-Based Introduction with Python*](https://third-bit.com/sdxpy/).
The chapter plan follows the original, but every chapter has been
rethought for Clojure: immutable data, functions as the main unit of
composition, protocols and multimethods for polymorphism, and the Java
platform underneath. Where the Python version leans on classes, this one
usually leans on maps and functions, and the chapters say why.

All of the code is in the
[repository]({{ site.repo_url }}) and runs with nothing but the Clojure
standard library and the JDK. Each chapter's tests can be run with
`clojure -M:test sdx.<slug>-test`.

## Chapters

{% assign chapters = site.pages | where_exp: "p", "p.nav_order" | sort: "nav_order" %}
<table>
<thead><tr><th>#</th><th>Chapter</th><th>What we build</th></tr></thead>
<tbody>
{% for ch in chapters %}
<tr><td>{{ ch.nav_order }}</td><td><a href="{{ ch.url | relative_url }}">{{ ch.title }}</a></td><td>{{ ch.lede }}</td></tr>
{% endfor %}
</tbody>
</table>

## Appendices

- [Glossary]({{ '/glossary/' | relative_url }})
- [Contributing]({{ '/contributing/' | relative_url }})
- [License]({{ '/license/' | relative_url }})
