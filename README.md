# Software Design by Example in Clojure

A Clojure port of Greg Wilson's
[*Software Design by Example: A Tool-Based Introduction with Python*](https://third-bit.com/sdxpy/).
Each chapter builds a small, working version of a tool programmers use
every day (a test runner, an interpreter, a build manager, a web server,
a virtual machine, and so on) in order to teach a few ideas about
software design. This port keeps the original's chapter plan but all of
the prose and code are written fresh for Clojure.

## Reading the book

The book lives in [`docs/`](docs/) as a Jekyll site that GitHub Pages
can serve without any extra configuration:

1. In the repository settings, open **Pages**.
2. Under **Build and deployment**, choose **Deploy from a branch**,
   select the `main` branch and the `/docs` folder, and save.
3. If the site is served from `https://USER.github.io/REPO/`, make sure
   `baseurl` in `docs/_config.yml` is `/REPO`.

To preview locally you need Ruby and Jekyll:

```sh
cd docs
gem install bundler jekyll
jekyll serve --baseurl ""
```

## Running the code

The code requires Java 11+ and the
[Clojure CLI](https://clojure.org/guides/install_clojure). No other
dependencies are used: every tool in the book is built from the Clojure
standard library and the JDK.

```sh
clojure -M:test                 # run every chapter's tests
clojure -M:test sdx.glob-test   # run one chapter's tests
clojure -M:dev                  # start a REPL with the test path loaded
```

Code for chapter *N* lives under `src/sdx/<slug>/` with tests in
`test/sdx/<slug>/`; the slug for each chapter is listed in
[`docs/index.md`](docs/index.md).

## License

The text is licensed under [CC-BY-NC-4.0](https://creativecommons.org/licenses/by-nc/4.0/)
and the code under the [Hippocratic License](https://firstdonoharm.dev/),
both matching the original. See [LICENSE.md](LICENSE.md).
