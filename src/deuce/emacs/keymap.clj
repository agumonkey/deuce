(ns deuce.emacs.keymap
  (:use [deuce.emacs-lisp :only (defun defvar setq) :as el])
  (:require [clojure.core :as c]
            [clojure.string :as s]
            [clojure.walk :as w]
            [deuce.emacs-lisp.cons :as cons]
            [deuce.emacs-lisp.globals :as globals]
            [deuce.emacs-lisp.parser :as parser]
            [deuce.emacs.alloc :as alloc]
            [deuce.emacs.buffer :as buffer]
            [deuce.emacs.chartab :as chartab]
            [deuce.emacs.data :as data]
            [deuce.emacs.editfns :as editfns]
            [deuce.emacs.fns :as fns]
            [deuce.emacs.textprop :as textprop])
  (:import [deuce.emacs.data CharTable])
  (:refer-clojure :exclude []))

(defvar emulation-mode-map-alists nil
  "List of keymap alists to use for emulations modes.
  It is intended for modes or packages using multiple minor-mode keymaps.
  Each element is a keymap alist just like `minor-mode-map-alist', or a
  symbol with a variable binding which is a keymap alist, and it is used
  the same way.  The \"active\" keymaps in each alist are used before
  `minor-mode-map-alist' and `minor-mode-overriding-map-alist'.")

(defvar minibuffer-local-ns-map nil
  "Local keymap for the minibuffer when spaces are not allowed.")

(defvar where-is-preferred-modifier nil
  "Preferred modifier key to use for `where-is'.
  When a single binding is requested, `where-is' will return one that
  uses this modifier key if possible.  If nil, or if no such binding
  exists, bindings using keys without modifiers (or only with meta) will
  be preferred.")

(defvar minor-mode-overriding-map-alist nil
  "Alist of keymaps to use for minor modes, in current major mode.
  This variable is an alist just like `minor-mode-map-alist', and it is
  used the same way (and before `minor-mode-map-alist'); however,
  it is provided for major modes to bind locally.")

(defvar minor-mode-map-alist nil
  "Alist of keymaps to use for minor modes.
  Each element looks like (VARIABLE . KEYMAP); KEYMAP is used to read
  key sequences and look up bindings if VARIABLE's value is non-nil.
  If two active keymaps bind the same key, the keymap appearing earlier
  in the list takes precedence.")

(defvar define-key-rebound-commands true
  "List of commands given new key bindings recently.
  This is used for internal purposes during Emacs startup;
  don't alter it yourself.")

(defvar minibuffer-local-map nil
  "Default keymap to use when reading from the minibuffer.")

(fns/put 'key-map 'char-table-extra-slots 0)

(def ^:private ^:dynamic *current-global-map* (atom nil))

(declare current-global-map current-minor-mode-maps keymapp keymap-parent set-keymap-parent lookup-key)

(defun make-sparse-keymap (&optional string)
  "Construct and return a new sparse keymap.
  Its car is `keymap' and its cdr is an alist of (CHAR . DEFINITION),
  which binds the character CHAR to DEFINITION, or (SYMBOL . DEFINITION),
  which binds the function key or mouse event SYMBOL to DEFINITION.
  Initially the alist is nil.

  The optional arg STRING supplies a menu name for the keymap
  in case you use it as a menu with `x-popup-menu'."
  (if string
    (alloc/list 'keymap string)
    (alloc/list 'keymap)))

(defn ^:private binding-this-map [keymap key]
  (let [char-table (second keymap)
        alist (if (data/char-table-p char-table) (fns/nthcdr 2 keymap) (data/cdr keymap))]
    (if (and (data/char-table-p (second keymap))
             (data/numberp key)
             (< (int key) (fns/length (second keymap))))
      (data/aref char-table key)
      (data/cdr (fns/assoc key alist)))))

(defn ^:private define-key-this-map [keymap key def]
  (let [char-table (second keymap)
        alist (if (data/char-table-p char-table) (fns/nthcdr 2 keymap) (data/cdr keymap))]
    (if (and (data/char-table-p char-table)
             (data/numberp key)
             (< (int key) (fns/length char-table)))
      (data/aset char-table key def)
      (if-let [existing (fns/assoc key alist)]
        (data/setcdr existing def)
        (data/setcdr (if (data/char-table-p char-table) (data/cdr keymap) keymap)
                     (alloc/cons (alloc/cons key def) alist))))))

(defn ^:private resolve-def [def]
  (cond (data/symbolp def)
   (try
     (let [maybe-keymap (data/symbol-function def)]
       (if (keymapp maybe-keymap)
         maybe-keymap
         def))
     (catch RuntimeException _
       def))

   (and (data/consp def)
        (string? (first def)))
   (data/cdr def)

   (and (data/consp def)
        (keymapp (first def)))
   (lookup-key (data/car def) (data/cdr def))

   :else def))

(defn ^:private binding-map [keymap key]
  (let [submap-or-binding (resolve-def (binding-this-map keymap (first key)))]
    (if-let [key (next key)]
      (if (keymapp submap-or-binding)
        (recur submap-or-binding key)
        (count key))
      submap-or-binding)))

(defn ^:private define-key-internal [keymap key def]
  (let [submap-or-binding (resolve-def (binding-this-map keymap (first key)))
        submap-or-binding (if-not submap-or-binding
                            (let [def (if (next key) (make-sparse-keymap) def)]
                              (define-key-this-map keymap
                                (first key) def)
                              def)
                            submap-or-binding)]
    (if-let [key (next key)]
      (if (keymapp submap-or-binding)
        (recur submap-or-binding key def)
        (throw (IllegalArgumentException.)))
      (define-key-this-map keymap (first key) def))))

(defun define-key (keymap key def)
  "In KEYMAP, define key sequence KEY as DEF.
  KEYMAP is a keymap.

  KEY is a string or a vector of symbols and characters, representing a
  sequence of keystrokes and events.  Non-ASCII characters with codes
  above 127 (such as ISO Latin-1) can be represented by vectors.
  Two types of vector have special meanings:
   [remap COMMAND] remaps any key binding for COMMAND.
   [t] creates a default definition, which applies to any event with no
      other definition in KEYMAP.

  DEF is anything that can be a key's definition:
   nil (means key is undefined in this keymap),
   a command (a Lisp function suitable for interactive calling),
   a string (treated as a keyboard macro),
   a keymap (to define a prefix key),
   a symbol (when the key is looked up, the symbol will stand for its
      function definition, which should at that time be one of the above,
      or another symbol whose function definition is used, etc.),
   a cons (STRING . DEFN), meaning that DEFN is the definition
      (DEFN should be a valid definition in its own right),
   or a cons (MAP . CHAR), meaning use definition of CHAR in keymap MAP,
   or an extended menu item definition.
   (See info node `(elisp)Extended Menu Items'.)

  If KEYMAP is a sparse keymap with a binding for KEY, the existing
  binding is altered.  If there is no binding for KEY, the new pair
  binding KEY to DEF is added at the front of KEYMAP."
  (let [real-key key
        key (if (string? key)
              (parser/parse-characters key (data/symbol-value 'meta-prefix-char))
              (el/check-type 'arrayp key))
        keymap (if (data/symbolp keymap) (data/symbol-value keymap) keymap)]
    (try
      (define-key-internal keymap key def)
      (catch IllegalArgumentException e
        (el/throw* 'error (format "Key sequence %s starts with non-prefix key %s"
                                  (s/join " " key) (s/join " " (butlast key))))))
    def))

(defun copy-keymap (keymap)
  "Return a copy of the keymap KEYMAP.
  The copy starts out with the same definitions of KEYMAP,
  but changing either the copy or KEYMAP does not affect the other.
  Any key definitions that are subkeymaps are recursively copied.
  However, a key definition which is a symbol whose definition is a keymap
  is not copied."
  (el/check-type 'keymapp keymap)
  (letfn [(copy [x]
            (condp some [x]
              keymapp (copy-keymap x)
              seq? (cons/maybe-seq (map copy x))
              data/char-table-p (let [x ^CharTable x]
                                  (CharTable. (.defalt x)
                                              (atom @(.parent x))
                                              (.purpose x)
                                              (let [contents (object-array (count (.contents x)))]
                                                (System/arraycopy (.contents x) 0  contents 0 (count (.contents x)))
                                                contents)
                                              (object-array (.extras x))))
              x))]
    (let [parent (keymap-parent keymap)
          keymap (apply alloc/list keymap)]
      (set-keymap-parent keymap nil)
      (doto (cons/maybe-seq (map copy keymap))
        (set-keymap-parent parent)))))

(defun map-keymap-internal (function keymap)
  "Call FUNCTION once for each event binding in KEYMAP.
  FUNCTION is called with two arguments: the event that is bound, and
  the definition it is bound to.  The event may be a character range.
  If KEYMAP has a parent, this function returns it without processing it."
  )

(defun current-local-map ()
  "Return current buffer's local keymap, or nil if it has none.
  Normally the local keymap is set by the major mode with `use-local-map'."
  (when (data/boundp 'keymap)
    (buffer/buffer-local-value 'keymap (buffer/current-buffer))))

(defun where-is-internal (definition &optional keymap firstonly noindirect no-remap)
  "Return list of keys that invoke DEFINITION.
  If KEYMAP is a keymap, search only KEYMAP and the global keymap.
  If KEYMAP is nil, search all the currently active keymaps, except
   for `overriding-local-map' (which is ignored).
  If KEYMAP is a list of keymaps, search only those keymaps.

  If optional 3rd arg FIRSTONLY is non-nil, return the first key sequence found,
  rather than a list of all possible key sequences.
  If FIRSTONLY is the symbol `non-ascii', return the first binding found,
  no matter what it is.
  If FIRSTONLY has another non-nil value, prefer bindings
  that use the modifier key specified in `where-is-preferred-modifier'
  (or their meta variants) and entirely reject menu bindings.

  If optional 4th arg NOINDIRECT is non-nil, don't follow indirections
  to other keymaps or slots.  This makes it possible to search for an
  indirect definition itself.

  The optional 5th arg NO-REMAP alters how command remapping is handled:

  - If another command OTHER-COMMAND is remapped to DEFINITION, normally
    search for the bindings of OTHER-COMMAND and include them in the
    returned list.  But if NO-REMAP is non-nil, include the vector
    [remap OTHER-COMMAND] in the returned list instead, without
    searching for those other bindings.

  - If DEFINITION is remapped to OTHER-COMMAND, normally return the
    bindings for OTHER-COMMAND.  But if NO-REMAP is non-nil, return the
    bindings for DEFINITION instead, ignoring its remapping."
  )

(defun keymapp (object)
  "Return t if OBJECT is a keymap.

  A keymap is a list (keymap . ALIST),
  or a symbol whose function definition is itself a keymap.
  ALIST elements look like (CHAR . DEFN) or (SYMBOL . DEFN);
  a vector of densely packed bindings for small character codes
  is also allowed as an element."
  (and (data/consp object) (= 'keymap (data/car object))))

(defun text-char-description (character)
  "Return a pretty description of file-character CHARACTER.
  Control characters turn into \"^char\", etc.  This differs from
  `single-key-description' which turns them into \"C-char\".
  Also, this function recognizes the 2**7 bit as the Meta character,
  whereas `single-key-description' uses the 2**27 bit for Meta.
  See Info node `(elisp)Describing Characters' for examples."
  )

;; http://www.gnu.org/software/emacs/manual/html_node/elisp/Searching-Keymaps.html
;; "Here is a pseudo-Lisp description of the order and conditions for searching them:"
;; (or (cond
;;      (overriding-terminal-local-map
;;       (find-in overriding-terminal-local-map))
;;      (overriding-local-map
;;       (find-in overriding-local-map))
;;      ((or (find-in (get-char-property (point) 'keymap))
;;           (find-in temp-map)
;;           (find-in-any emulation-mode-map-alists)
;;           (find-in-any minor-mode-overriding-map-alist)
;;           (find-in-any minor-mode-map-alist)
;;           (if (get-text-property (point) 'local-map)
;;               (find-in (get-char-property (point) 'local-map))
;;             (find-in (current-local-map))))))
;;     (find-in (current-global-map)))
;; "The function finally found might also be remapped. See Remapping Commands."
(defun current-active-maps (&optional olp position)
  "Return a list of the currently active keymaps.
  OLP if non-nil indicates that we should obey `overriding-local-map' and
  `overriding-terminal-local-map'.  POSITION can specify a click position
  like in the respective argument of `key-binding'."
  (cons/maybe-seq (remove nil? (concat (when olp [(or (data/symbol-value 'overriding-terminal-local-map)
                                                      (data/symbol-value 'overriding-local-map))])
                                       (current-minor-mode-maps)
                                       [(textprop/get-char-property (or position (editfns/point)) 'local-map)
                                        (current-local-map)
                                        (current-global-map)]))))

(defun key-binding (key &optional accept-default no-remap position)
  "Return the binding for command KEY in current keymaps.
  KEY is a string or vector, a sequence of keystrokes.
  The binding is probably a symbol with a function definition.

  Normally, `key-binding' ignores bindings for t, which act as default
  bindings, used when nothing else in the keymap applies; this makes it
  usable as a general function for probing keymaps.  However, if the
  optional second argument ACCEPT-DEFAULT is non-nil, `key-binding' does
  recognize the default bindings, just as `read-key-sequence' does.

  Like the normal command loop, `key-binding' will remap the command
  resulting from looking up KEY by looking up the command in the
  current keymaps.  However, if the optional third argument NO-REMAP
  is non-nil, `key-binding' returns the unmapped command.

  If KEY is a key sequence initiated with the mouse, the used keymaps
  will depend on the clicked mouse position with regard to the buffer
  and possible local keymaps on strings.

  If the optional argument POSITION is non-nil, it specifies a mouse
  position as returned by `event-start' and `event-end', and the lookup
  occurs in the keymaps associated with it instead of KEY.  It can also
  be a number or marker, in which case the keymap properties at the
  specified buffer position instead of point are used."
  (some identity (remove number? (map #(lookup-key % key accept-default) (current-active-maps nil position)))))

(defun map-keymap (function keymap)
  "Call FUNCTION once for each event binding in KEYMAP.
  FUNCTION is called with two arguments: the event that is bound, and
  the definition it is bound to.  The event may be a character range.

  If KEYMAP has a parent, the parent's bindings are included as well.
  This works recursively: if the parent has itself a parent, then the
  grandparent's bindings are also included and so on."
  (el/check-type 'keymapp keymap)
  (apply alloc/list
         (map (fn [x] ((el/fun function) (data/car x) (data/cdr x)))
              (filter data/consp (data/cdr keymap)))))

(defun keymap-prompt (map)
  "Return the prompt-string of a keymap MAP.
  If non-nil, the prompt is shown in the echo-area
  when reading a key-sequence to be looked-up in this keymap."
  (first (filter string? (take-while (complement '#{keymap}) (next map)))))

(defun apropos-internal (regexp &optional predicate)
  "Show all symbols whose names contain match for REGEXP.
  If optional 2nd arg PREDICATE is non-nil, (funcall PREDICATE SYMBOL) is done
  for each symbol and a symbol is mentioned only if that returns non-nil.
  Return list of symbols found."
  )

(defun set-keymap-parent (keymap parent)
  "Modify KEYMAP to set its parent map to PARENT.
  Return PARENT.  PARENT should be nil or another keymap."
  (el/check-type 'keymapp keymap)
  (when parent (el/check-type 'keymapp parent))
  (loop [x keymap]
    (when x
      (if (keymapp (data/cdr x))
        (data/setcdr x nil)
        (recur (data/cdr x)))))
  (fns/nconc keymap parent)
  parent)

(defun current-minor-mode-maps ()
  "Return a list of keymaps for the minor modes of the current buffer."
  (cons/maybe-seq (map data/cdr (filter (comp data/symbol-value data/car)
                                        (concat (data/symbol-value 'emulation-mode-map-alists)
                                                (data/symbol-value 'minor-mode-overriding-map-alist)
                                                (data/symbol-value 'minor-mode-map-alist))))))

(defun make-keymap (&optional string)
  "Construct and return a new keymap, of the form (keymap CHARTABLE . ALIST).
  CHARTABLE is a char-table that holds the bindings for all characters
  without modifiers.  All entries in it are initially nil, meaning
  \"command undefined\".  ALIST is an assoc-list which holds bindings for
  function keys, mouse events, and any other things that appear in the
  input stream.  Initially, ALIST is nil.

  The optional arg STRING supplies a menu name for the keymap
  in case you use it as a menu with `x-popup-menu'."
  (fns/nconc (alloc/list 'keymap (chartab/make-char-table 'keymap))
             (when string (alloc/list string))))

(defun describe-buffer-bindings (buffer &optional prefix menus)
  "Insert the list of all defined keys and their definitions.
  The list is inserted in the current buffer, while the bindings are
  looked up in BUFFER.
  The optional argument PREFIX, if non-nil, should be a key sequence;
  then we display only bindings that start with that prefix.
  The optional argument MENUS, if non-nil, says to mention menu bindings.
  (Ordinarily these are omitted from the output.)"
  )

(defun accessible-keymaps (keymap &optional prefix)
  "Find all keymaps accessible via prefix characters from KEYMAP.
  Returns a list of elements of the form (KEYS . MAP), where the sequence
  KEYS starting from KEYMAP gets you to MAP.  These elements are ordered
  so that the KEYS increase in length.  The first element is ([] . KEYMAP).
  An optional argument PREFIX, if non-nil, should be a key sequence;
  then the value includes only maps for prefixes that start with PREFIX."
  )

(defun lookup-key (keymap key &optional accept-default)
  "In keymap KEYMAP, look up key sequence KEY.  Return the definition.
  A value of nil means undefined.  See doc of `define-key'
  for kinds of definitions.

  A number as value means KEY is \"too long\";
  that is, characters or symbols in it except for the last one
  fail to be a valid sequence of prefix characters in KEYMAP.
  The number is how many characters at the front of KEY
  it takes to reach a non-prefix key.

  Normally, `lookup-key' ignores bindings for t, which act as default
  bindings, used when nothing else in the keymap applies; this makes it
  usable as a general function for probing keymaps.  However, if the
  third optional argument ACCEPT-DEFAULT is non-nil, `lookup-key' will
  recognize the default bindings, just as `read-key-sequence' does."
  (let [key (if (string? key)
              (parser/parse-characters key (data/symbol-value 'meta-prefix-char))
              (el/check-type 'arrayp key))
        keymap (if (data/symbolp keymap) (data/symbol-value keymap) keymap)]
    (if-let [def (binding-map keymap key)]
      def
      (if-let [default (and accept-default (binding-map keymap #el/vec [t]))]
        default
        (when-let [parent (keymap-parent keymap)]
          (recur parent key accept-default))))))

(defun key-description (keys &optional prefix)
  "Return a pretty description of key-sequence KEYS.
  Optional arg PREFIX is the sequence of keys leading up to KEYS.
  For example, [?C-x ?l] is converted into the string \"C-x l\".

  The `kbd' macro is an approximate inverse of this."
  )

(defun single-key-description (key &optional no-angles)
  "Return a pretty description of command character KEY.
  Control characters turn into C-whatever, etc.
  Optional argument NO-ANGLES non-nil means don't put angle brackets
  around function keys and event symbols."
  )

(defun use-local-map (keymap)
  "Select KEYMAP as the local keymap.
  If KEYMAP is nil, that means no local keymap."
  ;; This is not strictly correct, as these are some form of private buffer locals in Emacs.
  (el/check-type 'keymapp keymap)
  (data/make-local-variable 'keymap)
  (data/set 'keymap keymap))

(defun local-key-binding (keys &optional accept-default)
  "Return the binding for command KEYS in current local keymap only.
  KEYS is a string or vector, a sequence of keystrokes.
  The binding is probably a symbol with a function definition.

  If optional argument ACCEPT-DEFAULT is non-nil, recognize default
  bindings; see the description of `lookup-key' for more details about this."
  (when (current-local-map)
    (lookup-key (current-local-map) keys accept-default)))

(defun define-prefix-command (command &optional mapvar name)
  "Define COMMAND as a prefix command.  COMMAND should be a symbol.
  A new sparse keymap is stored as COMMAND's function definition and its value.
  If a second optional argument MAPVAR is given, the map is stored as
  its value instead of as COMMAND's value; but COMMAND is still defined
  as a function.
  The third optional argument NAME, if given, supplies a menu name
  string for the map.  This is required to use the keymap as a menu.
  This function returns COMMAND."
  (let [keymap (make-sparse-keymap name)]
    (data/fset command keymap)
    (data/set (or mapvar command) keymap)
    command))

(defun keymap-parent (keymap)
  "Return the parent keymap of KEYMAP.
  If KEYMAP has no parent, return nil."
  (loop [x (data/cdr keymap)]
    (when x
      (if (keymapp x)
        x
        (recur (data/cdr x))))))

(defun global-key-binding (keys &optional accept-default)
  "Return the binding for command KEYS in current global keymap only.
  KEYS is a string or vector, a sequence of keystrokes.
  The binding is probably a symbol with a function definition.
  This function's return values are the same as those of `lookup-key'
  (which see).

  If optional argument ACCEPT-DEFAULT is non-nil, recognize default
  bindings; see the description of `lookup-key' for more details about this."
  (when (current-global-map)
    (lookup-key (current-global-map) keys accept-default)))

(defun current-global-map ()
  "Return the current global keymap."
  @*current-global-map*)

(defun command-remapping (command &optional position keymaps)
  "Return the remapping for command COMMAND.
  Returns nil if COMMAND is not remapped (or not a symbol).

  If the optional argument POSITION is non-nil, it specifies a mouse
  position as returned by `event-start' and `event-end', and the
  remapping occurs in the keymaps associated with it.  It can also be a
  number or marker, in which case the keymap properties at the specified
  buffer position instead of point are used.  The KEYMAPS argument is
  ignored if POSITION is non-nil.

  If the optional argument KEYMAPS is non-nil, it should be a list of
  keymaps to search for command remapping.  Otherwise, search for the
  remapping in all currently active keymaps."
  )

(defun minor-mode-key-binding (key &optional accept-default)
  "Find the visible minor mode bindings of KEY.
  Return an alist of pairs (MODENAME . BINDING), where MODENAME is
  the symbol which names the minor mode binding KEY, and BINDING is
  KEY's definition in that mode.  In particular, if KEY has no
  minor-mode bindings, return nil.  If the first binding is a
  non-prefix, all subsequent bindings will be omitted, since they would
  be ignored.  Similarly, the list doesn't include non-prefix bindings
  that come after prefix bindings.

  If optional argument ACCEPT-DEFAULT is non-nil, recognize default
  bindings; see the description of `lookup-key' for more details about this."
  (some #(lookup-key % accept-default) (current-minor-mode-maps)))

(defun describe-vector (vector &optional describer)
  "Insert a description of contents of VECTOR.
  This is text showing the elements of vector matched against indices.
  DESCRIBER is the output function used; nil means use `princ'."
  )

(defun use-global-map (keymap)
  "Select KEYMAP as the global keymap."
  (el/check-type 'keymapp keymap)
  (reset! *current-global-map* keymap)
  nil)
