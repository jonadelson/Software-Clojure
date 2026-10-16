(ns sdx.layout.main
  "Command-line entry point: render a layout written as markup.

       clojure -M -m sdx.layout.main resources/layout/page.html 8"
  (:require [sdx.layout.markup :as m]
            [sdx.layout.render :as r]
            [sdx.layout.wrap :as w]))

(defn render-file
  "Read the layout markup in `filename` and render it as lines of text,
   wrapping rows wider than `max-width`."
  [filename max-width]
  (-> (slurp filename)
      m/parse-layout
      (w/wrap max-width)
      r/render))

(defn -main
  "Print the rendering of a layout file wrapped at the given width."
  [filename max-width]
  (doseq [line (render-file filename (parse-long max-width))]
    (println line)))
