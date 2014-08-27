;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

-;; Go emitter. Forked from f0dcc75573a42758f8c39b57d1747a2b4967327e, last update 562e6ccc9265979c586c2afc2f50a388e6c3c03e
-;; References to js in the public API are retained.

(ns cljs.compiler
  (:refer-clojure :exclude [munge macroexpand-1])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.reader :as reader]
            [cljs.env :as env]
            [cljs.tagged-literals :as tags]
            [cljs.analyzer :as ana])
  (:import java.lang.StringBuilder
           java.io.File))

(set! *warn-on-reflection* true)

(alter-var-root #'ana/*cljs-macros-path* (constantly "/cljs/go/core"))

;; next line is auto-generated by the build-script - Do not edit!
(def ^:dynamic *clojurescript-version*)

(defn clojurescript-version
  "Returns clojurescript version as a printable string."
  []
  (str
    (:major *clojurescript-version*)
    "."
    (:minor *clojurescript-version*)
    (when-let [i (:incremental *clojurescript-version*)]
      (str "." i))
    (when-let [q (:qualifier *clojurescript-version*)]
      (str "-" q))
    (when (:interim *clojurescript-version*)
      "-SNAPSHOT")))

(def js-reserved
  #{"break" "case" "chan" "const" "continue" "default"
    "defer" "else" "fallthrough" "for" "func" "go" "goto"
    "if" "import" "interface" "map" "package" "range"
    "return" "select" "struct" "switch" "type" "var"})

(def ^:dynamic *lexical-renames* {})

(def cljs-reserved-file-names #{"deps.cljs"})

(def ^:dynamic *go-return* nil)
(def ^:dynamic *go-line-numbers* false) ;; https://golang.org/cmd/gc/#hdr-Compiler_Directives

(defmacro ^:private debug-prn
  [& args]
  `(.println System/err (str ~@args)))

(defn ns-first-segments []
  (letfn [(get-first-ns-segment [ns] (first (string/split (str ns) #"\.")))]
    (map get-first-ns-segment (keys (::ana/namespaces @env/*compiler*)))))

; Helper fn
(defn shadow-depth [s]
  (let [{:keys [name info]} s]
    (loop [d 0, {:keys [shadow]} info]
      (cond
       shadow (recur (inc d) shadow)
       (some #{(str name)} (ns-first-segments)) (inc d)
       :else d))))

(defn go-public [s]
  (let [s (name s)]
    (if (re-find #"^[-_]" s)
      (str "X" s)
      (str (string/upper-case (subs s 0 1)) (subs s 1)))))

(defn go-type-fqn [s]
  (apply str (map go-public (string/split (str s) #"[./]"))))

(defn go-short-name [s]
  (last (string/split (str s) #"\.")))

(defn go-native-decorator [tag]
  ('{string js.JSString} tag))

(defn munge
  ([s] (munge s js-reserved))
  ([s reserved]
    (if (map? s)
      ; Unshadowing
      (let [{:keys [name field] :as info} s
            depth (shadow-depth s)
            renamed (*lexical-renames* (System/identityHashCode s))
            munged-name (munge (cond field (str "self__." name)
                                     renamed renamed
                                     :else name)
                               reserved)]
        (if (or field (zero? depth))
          munged-name
          (symbol (str munged-name "___" depth))))
      ; String munging
      (let [ss (string/replace (str s) #"\/(.)" ".$1") ; Division is special
            ss (apply str (map #(if (reserved %) (str % "_") %)
                               (string/split ss #"(?<=\.)|(?=\.)")))
            ms (string/split (clojure.lang.Compiler/munge ss) #"\.")
            ms (if (butlast ms)
                 (str (string/join "_"(butlast ms)) "." (last ms))
                 (str (last ms)))]
        (if (symbol? s)
          (symbol ms)
          ms)))))

(defn- comma-sep [xs]
  (interpose "," xs))

(defn- escape-char [^Character c]
  (let [cp (.hashCode c)]
    (case cp
      ; Handle printable escapes before ASCII
      34 "\\\""
      92 "\\\\"
      ; Handle non-printable escapes
      8 "\\b"
      12 "\\f"
      10 "\\n"
      13 "\\r"
      9 "\\t"
      (if (< 31 cp 127)
        c ; Print simple ASCII characters
        (format "\\u%04X" cp))))) ; Any other character is Unicode

(defn- escape-string [^CharSequence s]
  (let [sb (StringBuilder. (count s))]
    (doseq [c s]
      (.append sb (escape-char c)))
    (.toString sb)))

(defn- wrap-in-double-quotes [x]
  (str \" x \"))

(defmulti emit* :op)

(defn emit [ast]
  (env/ensure
    (emit* ast)))

(defn emits [& xs]
  (doseq [x xs]
    (cond
     (nil? x) nil
     (map? x) (emit x)
     (seq? x) (apply emits x)
     (fn? x)  (x)
     :else (let [s (print-str x)]
             (print s))))
  nil)

(defn emitln [& xs]
  (when-let [line (and *go-line-numbers*  (some (comp :line :env) xs))]
    (printf "\n//line %s:%d\n" ana/*cljs-file* line))
  (apply emits xs)
  (println)
  nil)

(defn ^String emit-str [expr]
  (with-out-str (emit expr)))

(defmulti emit-constant class)
(defmethod emit-constant nil [x] (emits "nil"))
(defmethod emit-constant Long [x] (emits "float64(" x ")"))
(defmethod emit-constant Integer [x] (emits "float64(" x ")")) ; reader puts Integers in metadata
(defmethod emit-constant Double [x] (emits x))
(defmethod emit-constant String [x]
  (emits (wrap-in-double-quotes (escape-string x))))
(defmethod emit-constant Boolean [x] (emits (if x "true" "false")))
(defmethod emit-constant Character [x]
  (emits (str "'" (escape-char x) "'")))

(defmethod emit-constant java.util.regex.Pattern [x]
  (if (= "" (str x))
    (emits "(&js.RegExp{Pattern: \"\", Flags: \"\"})")
    (let [[_ flags pattern] (re-find #"^(?:\(\?([idmsux]*)\))?(.*)" (str x))]
      (emits "(&js.RegExp{Pattern: \"" (.replaceAll (re-matcher #"/" pattern) "\\\\/") "\", Flags: \"" flags "\"})"))))

(defn emits-keyword [kw]
  (let [ns   (namespace kw)
        name (name kw)]
    (emits "(&CljsCoreKeyword{")
    (emits "Ns: ")
    (emit-constant ns)
    (emits ",")
    (emits "Name: ")
    (emit-constant name)
    (emits ",")
    (emits "Fqn: ")
    (emit-constant (if ns
                     (str ns "/" name)
                     name))
    (emits ",")
    (emits "Hash: ")
    (emit-constant (hash kw))
    (emits "})")))

(defmethod emit-constant clojure.lang.Keyword [x]
  (if (-> @env/*compiler* :opts :emit-constants)
    (let [value (-> @env/*compiler* ::ana/constant-table x)]
      (emits value))
    (emits-keyword x)))

(defmethod emit-constant clojure.lang.Symbol [x]
  (let [ns     (namespace x)
        name   (name x)
        symstr (if-not (nil? ns)
                 (str ns "/" name)
                 name)]
    (emits "(&CljsCoreSymbol{")
    (emits "Ns: ")
    (emit-constant ns)
    (emits ",")
    (emits "Name: ")
    (emit-constant name)
    (emits ",")
    (emits "Str: ")
    (emit-constant symstr)
    (emits ",")
    (emits "Hash: ")
    (emit-constant (hash x))
    (emits ",")
    (emits "Meta: ")
    (emit-constant nil)
    (emits "})")))

;; tagged literal support

(defmethod emit-constant java.util.Date [^java.util.Date date]
  (emits "(&js.Date{Millis: " (.getTime date) "})"))

(defmethod emit-constant java.util.UUID [^java.util.UUID uuid]
  (emits "(&CljsCoreUUID{Uuid: `" (.toString uuid) "`})"))

(defmacro emit-wrap [env & body]
  `(let [env# ~env]
     (when (= :return (:context env#)) (if-let [out# *go-return*]
                                         (emits out# " = ")
                                         (emits "return ")))
     ~@body
     (when-not (= :expr (:context env#)) (emitln))))

(defmethod emit* :no-op [m])

(defmethod emit* :var
  [{:keys [info env] :as arg}]
  (let [info (cond-> info (:type info) (update-in [:name] go-type-fqn))]
    ; We need a way to write bindings out to source maps and javascript
    ; without getting wrapped in an emit-wrap calls, otherwise we get
    ; e.g. (function greet(return x, return y) {}).
    (if (:binding-form? arg)
      ; Emit the arg map so shadowing is properly handled when munging
      ; (prevents duplicate fn-param-names)
      (emits (munge arg))
      (when-not (= :statement (:context env))
        (emit-wrap env (emits (munge (cond ;; this runs munge in a different order from most other things.
                                      (or ((hash-set ana/*cljs-ns* 'cljs.core) (:ns info))
                                          (:field info))
                                      (update-in info [:name] (comp go-public munge name))
                                      (:ns info)
                                      (update-in info [:name] #(str (namespace %) "." (-> % name munge go-public)))
                                      :else info))))))))
(defmethod emit* :meta
  [{:keys [expr meta env]}]
  (emit-wrap env
    (emits "With_meta.Invoke_Arity2(" expr "," meta ")")))

(def ^:private array-map-threshold 8)
(def ^:private obj-map-threshold 8)

(defn distinct-keys? [keys]
  (and (every? #(= (:op %) :constant) keys)
       (= (count (into #{} keys)) (count keys))))

(defmethod emit* :map
  [{:keys [env keys vals]}]
  (let [simple-keys? (every? #(or (string? %) (keyword? %)) keys)]
    (emit-wrap env
      (cond
        (zero? (count keys))
        (emits "CljsCorePersistentArrayMap_EMPTY")

        (<= (count keys) array-map-threshold)
        (if (distinct-keys? keys)
          (emits "CljsCorePersistentArrayMap{nil, " (count keys) ", []interface{}{"
            (comma-sep (interleave keys vals))
            "}, nil}")
          (emits "CljsCorePersistentArrayMap_fromArray.Invoke_Arity3([]interface{}{"
            (comma-sep (interleave keys vals))
            "}, true, false).(CljsCorePersistentArrayMap)"))

        :else
        (emits "CljsCorePersistentHashMap_fromArrays.Invoke_Arity2([]interface{}{"
               (comma-sep keys)
               "},[]interface{}{"
               (comma-sep vals)
               "}).(CljsCorePersistentHashMap)")))))

(defmethod emit* :list
  [{:keys [items env]}]
  (emit-wrap env
    (if (empty? items)
      (emits "CljsCoreList_EMPTY")
      (emits "List(" (comma-sep items) ")"))))

(defmethod emit* :vector
  [{:keys [items env]}]
  (emit-wrap env
    (if (empty? items)
      (emits "CljsCorePersistentVector_EMPTY")
      (let [cnt (count items)]
        (if (< cnt 32)
          (emits "CljsCorePersistentVector{nil, " cnt
            ", 5, CljsCorePersistentVector_EMPTY_NODE, []interface{}{"  (comma-sep items) "}, nil}")
          (emits "CljsCorePersistentVector_fromArray.Invoke_Arity2([]interface{}{" (comma-sep items) "}, true).(CljsCorePersistentVector)"))))))

(defn distinct-constants? [items]
  (and (every? #(= (:op %) :constant) items)
       (= (count (into #{} items)) (count items))))

(defmethod emit* :set
  [{:keys [items env]}]
  (emit-wrap env
    (cond
      (empty? items)
      (emits "CljsCorePersistentHashSet_EMPTY")

      (distinct-constants? items)
      (emits "CljsCorePersistentHashSet{nil, CljsCorePersistentArrayMap{nil, " (count items) ", []interface{}{"
        (comma-sep (interleave items (repeat "nil"))) "}, nil}, nil}")

      :else (emits "CljsCorePersistentHashSet_fromArray.Invoke_Arity2([]interface{}{" (comma-sep items) "}, true).(CljsCorePersistentHashSet)"))))

(defmethod emit* :js-value
  [{:keys [items js-type env]}]
  (emit-wrap env
    (if (= js-type :object)
      (do
        (emits "map[string]interface{}{")
        (when-let [items (seq items)]
          (let [[[k v] & r] items]
            (emits "\"" (name k) "\": " v)
            (doseq [[k v] r]
              (emits ", \"" (name k) "\": " v))))
        (emits "}"))
      (emits "[]interface{}{" (comma-sep items) "}"))))

(defmethod emit* :constant
  [{:keys [form env]}]
  (when-not (= :statement (:context env))
    (emit-wrap env (emit-constant form))))

(defn truthy-constant? [{:keys [op form]}]
  (and (= op :constant)
       form
       (not (or (and (string? form) (= form ""))
                (and (number? form) (zero? form))))))

(defn falsey-constant? [{:keys [op form]}]
  (and (= op :constant)
       (or (false? form) (nil? form))))

(defn safe-test? [env e]
  (let [tag (ana/infer-tag env e)]
    (or (#{'boolean 'seq} tag) (truthy-constant? e))))

(defmethod emit* :if
  [{:keys [test then else env unchecked]}]
  (let [context (:context env)
        checked (not (or unchecked (safe-test? env test)))]
    (cond
      (truthy-constant? test) (emitln then)
      (falsey-constant? test) (emitln else)
      :else
      (if (= :expr context)
        (emits "func() interface{} { if " (when checked "Truth_") "(" test ") { return " then "} else { return " else "} }()")
        (do
          (if checked
            (emitln "if Truth_(" test ") {")
            (emitln "if " test " {"))
          (emitln then "} else {")
          (emitln else "}"))))))

(defmethod emit* :case*
  [{:keys [v tests thens default env]}]
  (when (= (:context env) :expr)
    (emitln "func() interface{} {"))
  (let [gs (gensym "caseval__")]
    (when (= :expr (:context env))
      (emitln "var " gs ""))
    (emitln "switch " v " {")
    (doseq [[ts then] (partition 2 (interleave tests thens))]
      (doseq [test ts]
        (emitln "case " test ":"))
      (if (= :expr (:context env))
        (emitln gs "=" then)
        (emitln then)))
    (when default
      (emitln "default:")
      (if (= :expr (:context env))
        (emitln gs "=" default)
        (emitln default)))
    (emitln "}")
    (when (= :expr (:context env))
      (emitln "return " gs "}()"))))

(defmethod emit* :throw
  [{:keys [throw env]}]
  (if (= :expr (:context env))
    (emits "func() interface{} {panic(" throw ")}()")
    (emitln "panic(" throw ")")))

(defn emit-comment
  "Emit a nicely formatted comment string."
  [doc jsdoc]
  (let [docs (when doc [doc])
        docs (if jsdoc (concat docs jsdoc) docs)
        docs (remove nil? docs)]
    (letfn [(print-comment-lines [e] (doseq [next-line (string/split-lines e)]
                                       (emitln "* " (string/trim next-line))))]
      (when (seq docs)
        (emitln "/**")
        (doseq [e docs]
          (when e
            (print-comment-lines e)))
        (emitln "*/")))))

(defmethod emit* :def
  [{:keys [name var init env doc export]}]
  (let [mname (-> name munge go-short-name go-public)]
    (when init
      (emit-comment doc (:jsdoc init))
      (emitln "var " mname
              (when (= 'clj-nil (:tag init))
                " interface{}")
              " = " (assoc-in init [:name :name] mname))
      ;; NOTE: JavaScriptCore does not like this under advanced compilation
      ;; this change was primarily for REPL interactions - David
      ;(emits " = (typeof " mname " != 'undefined') ? " mname " : undefined")
      (when-not (= :expr (:context env)) (emitln))
      (when-let [export (and export (-> export munge go-short-name go-public))]
        (when-not (= export mname)
          (emitln "var "export  " = " mname))))))

(defn go-type [tag]
  ('{number "float64" boolean "bool" string "string" array "[]interface{}"} tag "interface{}"))

(defn go-short-type [tag]
  ('{number "F" boolean "B" string "S" array "A"} tag "I"))

(defn go-type-suffix [params ret-tag]
  (apply str (concat (map (comp go-short-type :tag) params) [(go-short-type ret-tag)])))

(defn emit-fn-signature [params ret-tag]
  (let [typed-params (for [{:keys [name tag]} params]
                       (str (munge name) " " (go-type tag)))]
    (emits "(" (comma-sep typed-params) ") " (go-type ret-tag))))

(defn assign-to-blank [bindings]
  (when-let [bindings (seq (remove '#{_} (map munge bindings)))]
    (emitln (comma-sep (repeat (count bindings) "_"))
            " = "
            (comma-sep bindings))))

(defn emit-fn-body [type expr recurs]
  (when recurs (emitln "for {"))
  (emits expr)
  (when recurs
    (emitln "panic(&js.Error{`Unreachable`})")
    (emitln "}")))

(defn emit-fn-method
  [{:keys [type params expr env recurs]} ret-tag]
  (emit-wrap env
    (emits "func")
    (emit-fn-signature params ret-tag)
    (emits "{")
    (emit-fn-body type expr recurs)
    (emits "}")))

(defn emit-protocol-method
  [name {:keys [type params expr env recurs]} ret-tag]
  (emit-wrap env
    (emits "func (self__ *" (-> params first :tag go-type-fqn) ") "
           (-> name munge go-short-name go-public) "_Arity" (count params))
    (emit-fn-signature (rest params) ret-tag)
    (emits "{")
    (emit-fn-body type expr recurs)
    (emits "}")))

(defn emit-variadic-fn-method
  [{:keys [type name variadic params expr env recurs max-fixed-arity] :as f}]
  (let [varargs (string/join "_" (map :name params))]
    (emit-wrap env
      (emits "func(")
      (emitln varargs " ...interface{}" ") interface{} {")
      (doseq [[idx p] (map-indexed vector (butlast params))]
        (emitln "var " p " = " varargs "[" idx "]"))
      (emitln "var " (last params) " = Array_seq.Invoke_Arity1(" varargs "[" max-fixed-arity ":]" ")")
      (assign-to-blank params)
      (emit-fn-body type expr recurs)
      (emits "}"))))

(defmethod emit* :fn
  [{:keys [name env methods protocol-impl max-fixed-arity variadic recur-frames loop-lets]}]
  ;;fn statements get erased, serve no purpose and can pollute scope if named
  (when (or (not= :statement (:context env)) protocol-impl)
    (let [loop-locals (->> (concat (mapcat :params (filter #(and % @(:flag %)) recur-frames))
                                   (mapcat :params loop-lets))
                           (map munge)
                           seq)]
      (when loop-locals
        (when (= :return (:context env))
          (emits "return "))
        (emitln "func(" (comma-sep loop-locals) ") *AFnPrimitive {")
        (when-not (= :return (:context env))
          (emits "return ")))
      (let [name (or name (gensym))
            mname (munge name)]
        (when (= :return (:context env))
          (emits "return "))
        (when-not protocol-impl
          (emitln "func(" mname " *AFnPrimitive) *AFnPrimitive {")
          (emits "return Fn(" mname ", "))
        (loop [[meth & methods] methods]
          (cond
           protocol-impl (emit-protocol-method mname meth (:ret-tag name))
           (:variadic meth) (emit-variadic-fn-method meth)
           :else (emit-fn-method meth (:ret-tag name)))
          (when methods
            (emits ", ")
            (recur methods)))
        (when-not protocol-impl
          (emitln ").(*AFnPrimitive)")
          (emits "}(&AFnPrimitive{})")))
      (when loop-locals
        (emitln "}(" (comma-sep loop-locals) "))"))))
  (when (= '-main (:name name))
    (emitln)
    (emitln"func init() {")
    (emitln "STAR_main_cli_fn_STAR_ = _main")
    (emitln "}")))

(defmethod emit* :do
  [{:keys [statements ret env]}]
  (let [context (:context env)]
    (when (and statements (= :expr context)) (emits "func() interface{} {"))
    (when statements
      (emits statements))
    (emit ret)
    (when (and statements (= :expr context)) (emits "}()"))))

(defmethod emit* :try
  [{:keys [env try catch name finally]}]
  (let [context (:context env)]
    (if (or name finally)
      (let [out (when name (gensym "return__"))]
        (when (= :return (:context env))
          (emits "return "))
        (emits "func() (" out " interface{}) {")
        (when finally
          (assert (not= :constant (:op finally)) "finally block cannot contain constant")
          (emitln "defer func() {" finally "}()"))
        (when name
          (binding [*go-return* (or out *go-return*)]
            (emitln "defer func() { if " (munge name) " := recover(); "
                    (munge name) " != nil {" catch "}}()")))
        (emits "{" try "}")
        (emits "}()"))
      (emits try))))

(defn emit-let
  [{:keys [bindings expr env]} is-loop]
  (let [context (:context env)]
    (if (= :expr context)
      (emits "func() interface{} {")
      (emits "{"))
    (binding [*lexical-renames* (into *lexical-renames*
                                      (when (= :statement context)
                                        (map #(vector (System/identityHashCode %)
                                                      (gensym (str (:name %) "-")))
                                             bindings)))]
      (doseq [{:keys [init] :as binding} bindings]
        (emitln "var " binding " = " init))  ; Binding will be treated as a var
      (assign-to-blank bindings)
      (when is-loop (emitln "for {"))
      (emits expr)
      (when is-loop
        (emitln "panic(&js.Error{`Unreachable`})")
        (emitln "}")))
    (if (= :expr context)
      (emits "}()")
      (emits "}"))))

(defmethod emit* :let [ast]
  (emit-let ast false))

(defmethod emit* :loop [ast]
  (emit-let ast true))

(defmethod emit* :recur
  [{:keys [frame exprs env]}]
  (let [temps (vec (take (count exprs) (repeatedly gensym)))
        params (:params frame)]
    (emitln "{")
    (dotimes [i (count exprs)]
      (emitln "var " (temps i) " = " (exprs i)))
    (dotimes [i (count exprs)]
      (emitln (munge (params i)) " = " (temps i)))
    (emitln "continue")
    (emitln "}")))

(defmethod emit* :letfn
  [{:keys [bindings expr env]}]
  (let [context (:context env)]
    (if (= :expr context)
      (emits "func() interface{} {")
      (emitln "{"))
    (emitln "var " (string/join ", " (map munge bindings)) " *AFnPrimitive")
    (doseq [{:keys [init] :as binding} bindings]
      (emitln (munge binding) " = " init))
    (assign-to-blank bindings)
    (emits expr)
    (if (= :expr context)
      (emits "}()")
      (emitln "}"))))

(defmethod emit* :invoke
  [{:keys [f args env] :as expr}]
  (let [info (:info f)
        fn? (and ana/*cljs-static-fns*
                 (not (:dynamic info))
                 (:fn-var info))
        protocol (:protocol info)
        tag      (ana/infer-tag env (first (:args expr)))
        proto? (and protocol tag
                 (or (and ana/*cljs-static-fns* protocol (= tag 'not-native))
                     (and
                       (or ana/*cljs-static-fns*
                           (:protocol-inline env))
                       (or (= protocol tag)
                           ;; ignore new type hints for now - David
                           (and (not (set? tag))
                                (not ('#{any clj clj-or-nil} tag))
                                (when-let [ps (:protocols (ana/resolve-existing-var (dissoc env :locals) tag))]
                                  (ps protocol)))))))
        opt-not? (and (= (:name info) 'cljs.core/not)
                      (= (ana/infer-tag env (first (:args expr))) 'boolean))
        ns (:ns info)
        js? ('#{js Math} ns)
        goog? (when ns
                (or (= ns 'goog)
                    (when-let [ns-str (str ns)]
                      (= (get (string/split ns-str #"\.") 0 nil) "goog"))))
        keyword? (and (= (-> f :op) :constant)
                      (keyword? (-> f :form)))
        arity (count args)
        [params] ((group-by count (:method-params info)) arity)
        primitive-sig (go-type-suffix params (-> f :info :ret-tag))
        has-primitives? (not (re-find #"^I+$" primitive-sig))
        tags-match? (= (map :tag params) (map :tag args))
        variadic-invoke (and (:variadic info)
                             (> arity (:max-fixed-arity info)))
        coerce? (:binding-form? info)]
    (emit-wrap env
      (cond
       opt-not?
       (emits "!(" (first args) ")")

       proto? ;; needs to take the imported name of protocols into account.
       (let [pimpl (str (-> info :name name munge go-public) "_Arity" (count args))]
         (emits (first args) "." pimpl "(" (comma-sep (rest args)) ")"))

       keyword?
       (emits f ".Invoke_Arity" arity "(" (comma-sep args) ")")

       variadic-invoke
       (emits f ".Invoke_ArityVariadic(" (comma-sep args) ")")

       (or js? goog?)
       (emits f "(" (comma-sep args)  ")")

       (and has-primitives? tags-match?)
       (emits f ".Arity" arity primitive-sig "(" (comma-sep args) ")")

       :else
       (emits f (when coerce? ".(IFn)") ".Invoke_Arity" arity "(" (comma-sep args) ")")))))

(defmethod emit* :new
  [{:keys [ctor args env]}]
  (emit-wrap env
             (emits "(&" (if ('#{js goog.string} (-> ctor :info :ns))
                           ctor
                           (update-in ctor [:info :name] (comp munge go-type-fqn))) "{"
           (comma-sep args)
           "})")))

;; This is a hack that tracks static fields on types, like (set! (.-EMPTY PeristentVector) ...)
(defn maybe-define-static-field-var [{:keys [target field]}]
  (when (-> target :info :type)
    (let [field (symbol (str (-> target :info :name go-type-fqn) "_" field))
          ns (-> target :info :ns)
          ks [::ana/namespaces ns :defs field]]
      (when (and (not (get-in @env/*compiler* ks)) (= ns ana/*cljs-ns*))
        (swap! env/*compiler* assoc-in ks {:name (symbol (name ns) (name field))})
        (emits "var ")))))

(defmethod emit* :set!
  [{:keys [target val env]}]
  (emit-wrap env
    (when (= :statement (:context env))
      (maybe-define-static-field-var target))
    (when (#{:expr :return} (:context env))
      (emits "func() interface{} {"))
    (emitln target " = " val)
    (when (#{:expr :return} (:context env))
      (emitln " return " target)
      (emits "}()"))))

(defmethod emit* :ns
  [{:keys [name requires uses require-macros env]}]
  (emitln "// " name)
  (emitln "package " (last (string/split (str (munge name)) #"\.")))
  (emitln)
  (emitln "import (")
  (when-not (= name 'cljs.core)
    (emitln "\t" "." " " (wrap-in-double-quotes "github.com/hraberg/cljs.go/cljs/core")))
  (doseq [lib (distinct (into (vals requires) (vals uses)))]
    (emitln "\t" (string/replace (munge lib) "." "_") " " (wrap-in-double-quotes (string/replace (munge lib) #"[._]" "/"))))
  (emitln ")")
  (emitln))

(defmethod emit* :deftype*
  [{:keys [t fields pmasks]}]
  (let [fields (map (comp go-public munge) fields)]
    (emitln "type " (-> t go-type-fqn munge) " struct { " (comma-sep fields) (when (seq fields) " interface{}") " }")))

(defmethod emit* :defrecord*
  [{:keys [t fields pmasks]}]
  (let [fields (map (comp go-public munge) fields)]
    (emitln "type " (-> t go-type-fqn munge) " struct { " (comma-sep fields) ", X__meta, X__extmap interface{} }")))

(defmethod emit* :dot
  [{:keys [target field method args env]}]
  (let [decorator (go-native-decorator (:tag target))
        type? (-> target :info :type)]
    (emit-wrap env
               (if field
                 (emits target (if type? "_" ".") (munge (go-public field) #{}))
                 (emits (when decorator (str decorator "("))
                        target
                        (when decorator ")")
                        (if type? "_" ".")
                        (munge (go-public method) #{})
                        (when type?
                          (str ".Invoke_Arity" (count args)))
                        "("
                        (comma-sep args)
                        ")")))))

(defn go-unbox [x]
  (when x
    (str (emit-str x)
         (when (or (not= 'number (:tag x))
                   (and (= :invoke (:op x))
                        (not= 'number (-> x :f :info :ret-tag))))
           ".(float64)"))))

(defmethod emit* :js
  [{:keys [env code segs args numeric]}]
  (emit-wrap env
             (cond
              code (emits code)
              :else (emits (interleave (concat segs (repeat nil))
                                       (map (if numeric go-unbox identity) (concat args [nil])))))))

(defn rename-to-js
  "Change the file extension from .cljs to .js. Takes a File or a
  String. Always returns a String."
  [file-str]
  (clojure.string/replace file-str #"\.cljs$" ".go"))

(defn mkdirs
  "Create all parent directories for the passed file."
  [^File f]
  (.mkdirs (.getParentFile (.getCanonicalFile f))))

(defmacro with-core-cljs
  "Ensure that core.cljs has been loaded."
  [& body]
  `(do (when-not (get-in @env/*compiler* [::ana/namespaces 'cljs.core :defs])
         (ana/analyze-file "cljs/core.cljs"))
       ~@body))

(defn url-path [^File f]
  (.getPath (.toURL (.toURI f))))

(defn compile-file*
  ([src dest] (compile-file* src dest nil))
  ([src dest opts]
    (env/ensure
      (with-core-cljs
        (with-open [out ^java.io.Writer (io/make-writer dest {})]
          (binding [*out* out
                    ana/*cljs-ns* 'cljs.user
                    ana/*cljs-file* (.getPath ^File src)
                    reader/*alias-map* (or reader/*alias-map* {})
                    *go-line-numbers* (boolean (:source-map opts))]
            (emitln "// Compiled by ClojureScript to Go " (clojurescript-version))
            (loop [forms (ana/forms-seq src)
                   ns-name nil
                   deps nil]
              (if (seq forms)
                (let [env (ana/empty-env)
                      ast (ana/analyze env (first forms) nil opts)]
                  (do (emit ast)
                    (if (= (:op ast) :ns)
                      (recur (rest forms) (:name ast) (merge (:uses ast) (:requires ast)))
                      (recur (rest forms) ns-name deps))))))))))))

(defn compiled-by-version [^File f]
  (with-open [reader (io/reader f)]
    (let [match (->> reader line-seq first
                     (re-matches #".*ClojureScript (.*)$"))]
      (and match (second match)))))

(defn requires-compilation?
  "Return true if the src file requires compilation."
  ([src dest] (requires-compilation? src dest nil))
  ([^File src ^File dest opts]
    (env/ensure
      (or (not (.exists dest))
          (> (.lastModified src) (.lastModified dest))
          (let [version' (compiled-by-version dest)
                version  (clojurescript-version)]
            (and version (not= version version')))))))

(defn parse-ns
  ([src] (parse-ns src nil nil))
  ([src dest opts]
    (env/ensure
      (let [namespaces' (::ana/namespaces @env/*compiler*)
            ret
            (binding [ana/*cljs-ns* 'cljs.user
                      ana/*analyze-deps* false]
              (loop [forms (ana/forms-seq src)]
                (if (seq forms)
                  (let [env (ana/empty-env)
                        ast (ana/no-warn (ana/analyze env (first forms) nil opts))]
                    (if (= (:op ast) :ns)
                      (let [ns-name (:name ast)
                            deps    (merge (:uses ast) (:requires ast))]
                        (merge
                          {:ns (or ns-name 'cljs.user)
                           :provides [ns-name]
                           :requires (if (= ns-name 'cljs.core)
                                       (set (vals deps))
                                       (cond-> (conj (set (vals deps)) 'cljs.core)
                                         (get-in @env/*compiler* [:opts :emit-constants])
                                         (conj 'constants-table)))
                           :file dest
                           :source-file src}
                          (when (and dest (.exists ^File dest))
                            {:lines (with-open [reader (io/reader dest)]
                                      (-> reader line-seq count))})))
                      (recur (rest forms)))))))]
        ;; TODO this _was_ a reset! of the old ana/namespaces atom; should we capture and
        ;; then restore the entirety of env/*compiler* here instead?
        (swap! env/*compiler* assoc ::ana/namespaces namespaces')
        ret))))

(defn compile-file
  "Compiles src to a file of the same name, but with a .js extension,
   in the src file's directory.

   With dest argument, write file to provided location. If the dest
   argument is a file outside the source tree, missing parent
   directories will be created. The src file will only be compiled if
   the dest file has an older modification time.

   Both src and dest may be either a String or a File.

   Returns a map containing {:ns .. :provides .. :requires .. :file ..}.
   If the file was not compiled returns only {:file ...}"
  ([src]
    (let [dest (rename-to-js src)]
      (compile-file src dest nil)))
  ([src dest]
    (compile-file src dest nil))
  ([src dest opts]
    (let [src-file (io/file src)
          dest-file (io/file dest)]
      (if (.exists src-file)
        (try
          (let [{ns :ns :as ns-info} (parse-ns src-file dest-file opts)]
            (if (requires-compilation? src-file dest-file opts)
              (do (mkdirs dest-file)
                (when (contains? (::ana/namespaces @env/*compiler*) ns)
                  (swap! env/*compiler* update-in [::ana/namespaces] dissoc ns))
                (compile-file* src-file dest-file opts))
              (do
                (when-not (contains? (::ana/namespaces @env/*compiler*) ns)
                  (with-core-cljs
                    (ana/analyze-file src-file)))
                ns-info)))
          (catch Exception e
            (throw (ex-info (str "failed compiling file:" src) {:file src} e))))
        (throw (java.io.FileNotFoundException. (str "The file " src " does not exist.")))))))

(defn path-seq
  [file-str]
  (->> File/separator
       java.util.regex.Pattern/quote
       re-pattern
       (string/split file-str)))

(defn to-path
  ([parts]
     (to-path parts File/separator))
  ([parts sep]
    (apply str (interpose sep parts))))

(defn ^File to-target-file
  [target cljs-file]
  (let [relative-path (string/split
                        (ana/munge-path
                          (str (:ns (parse-ns cljs-file)))) #"\.")
        parents (butlast relative-path)]
    (io/file
      (io/file (to-path (cons target parents)))
      (str (last relative-path) ".go"))))

(defn cljs-files-in
  "Return a sequence of all .cljs files in the given directory."
  [dir]
  (filter #(let [name (.getName ^File %)]
             (and (.endsWith name ".cljs")
                  (not= \. (first name))
                  (not (contains? cljs-reserved-file-names name))))
          (file-seq dir)))

(defn compile-root
  "Looks recursively in src-dir for .cljs files and compiles them to
   .js files. If target-dir is provided, output will go into this
   directory mirroring the source directory structure. Returns a list
   of maps containing information about each file which was compiled
   in dependency order."
  ([src-dir]
     (compile-root src-dir "out"))
  ([src-dir target-dir]
     (compile-root src-dir target-dir nil))
  ([src-dir target-dir opts]
     (swap! env/*compiler* assoc :root src-dir)
     (let [src-dir-file (io/file src-dir)]
       (loop [cljs-files (cljs-files-in src-dir-file)
              output-files []]
         (if (seq cljs-files)
           (let [cljs-file (first cljs-files)
                 output-file (to-target-file target-dir cljs-file)
                 ns-info (compile-file cljs-file output-file opts)]
             (recur (rest cljs-files) (conj output-files (assoc ns-info :file-name (.getPath output-file)))))
           output-files)))))

;; TODO: needs fixing, table will include other things than keywords - David

(defn emit-constants-table [table]
  (doseq [[keyword value] table]
    (emits value " = ")
    (emits-keyword keyword)
    (emitln)))

(defn emit-constants-table-to-file [table dest]
  (with-open [out ^java.io.Writer (io/make-writer dest {})]
    (binding [*out* out]
      (emit-constants-table table))))
