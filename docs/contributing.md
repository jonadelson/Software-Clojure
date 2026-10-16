---
title: Contributing
---

Contributions are welcome. This page explains how the book is organized
so that new material fits with what is already here.

## How a chapter is organized

Every chapter builds one tool, and every chapter has the same shape:

1. **An opening** of two or three paragraphs that says what we are going to
   build and which design ideas it will teach.
2. **Three to six sections**, each of which adds one idea or one feature.
   Each section shows the code, explains the decisions behind it, and
   demonstrates it working, usually with a short test.
3. **A summary**: a bulleted list of the design ideas the chapter
   introduced.
4. **Exercises** that extend or modify the tool.

Chapters are written for people who know the basics of Clojure (maps,
vectors, `defn`, `let`, destructuring, `clojure.test`) but who may not
have used protocols, records, multimethods, macros, atoms, or Java
interop before. Explain each of those the first time a chapter needs it.

## Where things go

| What | Where |
|------|-------|
| Chapter text | `docs/NN-slug.md` |
| Code | `src/sdx/slug.clj` or `src/sdx/slug/*.clj` |
| Tests | `test/sdx/slug_test.clj` or `test/sdx/slug/*_test.clj` |
| Sample data | `resources/slug/` |

Each chapter file starts with front matter giving its title, position,
and a one-line description:

```yaml
---
title: "Matching Patterns"
nav_order: 4
lede: "A glob matcher built out of small composable pieces."
---
```

The site layout uses `nav_order` to build the sidebar and the
previous/next links. Link to another chapter with a relative URL such as
`[the parser](../05-parser/)`.

## Code conventions

- Use only the Clojure standard library and the JDK. The point of the
  book is to see how things work, so we build rather than import.
- Prefer pure functions over state. When state is unavoidable, use an
  atom and say why.
- Give every public function a docstring.
- When a chapter builds a tool in stages, put each stage in its own
  namespace (`sdx.glob.lit`, `sdx.glob.any`, …) so that readers can load
  and run each one.
- Every chapter has tests that run with `clojure -M:test sdx.slug-test`.
  Tests are part of the teaching: show readers how a design decision
  makes something easier to test.

## Writing conventions

- Show code in fenced blocks marked `clojure`. Show REPL results as a
  comment on the following line: `;; => 42`.
- Put callouts (asides, warnings, historical notes) in a blockquote whose
  first line is bold.
- Bold a term the first time it is defined.
- Everything here is original work. Do not copy prose or code from the
  Python book; describe the same ideas in your own words and code.
