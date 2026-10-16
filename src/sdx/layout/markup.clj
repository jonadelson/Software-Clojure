(ns sdx.layout.markup
  "Describing a layout as markup, using the HTML parser from chapter 11:

       <col>
         <row><leaf width=\"2\" height=\"1\" fill=\"a\"/><leaf width=\"1\" height=\"2\"/></row>
         <leaf width=\"3\" height=\"1\" fill=\"c\"/>
       </col>

   Text between elements (usually indentation) is ignored."
  (:require [sdx.html.parser :as p]
            [sdx.layout.size :as s]))

(defn- attr-int
  "The whole-number value of attribute `name` on `node`, or an error."
  [node name]
  (let [value (get-in node [:attrs name])]
    (or (and value (parse-long value))
        (throw (ex-info (str "<" (:tag node) "> needs a whole-number " name)
                        {:tag (:tag node) :attr name :value value})))))

(defn- child-elements
  "The element children of `node`, skipping text."
  [node]
  (filter p/element? (:children node)))

(defn block
  "Turn a parsed <leaf>, <row>, or <col> element into a block."
  [node]
  (case (:tag node)
    "leaf" (s/leaf (attr-int node "width")
                   (attr-int node "height")
                   (first (get-in node [:attrs "fill"] "x")))
    "row" (apply s/row (map block (child-elements node)))
    "col" (apply s/col (map block (child-elements node)))
    (throw (ex-info (str "unknown block <" (:tag node) ">") {:tag (:tag node)}))))

(defn parse-layout
  "Parse markup describing a layout into a block tree. The markup must
   have exactly one top-level element."
  [text]
  (let [elements (child-elements (p/parse text))]
    (when (not= 1 (count elements))
      (throw (ex-info (str "expected one top-level block, found " (count elements))
                      {:count (count elements)})))
    (block (first elements))))
