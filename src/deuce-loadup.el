;;; loadup.el --- load up standardly loaded Lisp files for Emacs

;; Copyright (C) 1985-1986, 1992, 1994, 2001-2012
;;   Free Software Foundation, Inc.

;; Maintainer: FSF
;; Keywords: internal
;; Package: emacs

;; This file is part of GNU Emacs.

;; GNU Emacs is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.

;; GNU Emacs is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.

;; You should have received a copy of the GNU General Public License
;; along with GNU Emacs.  If not, see <http://www.gnu.org/licenses/>.

;;; Commentary:

;; This is loaded into a bare Emacs to make a dumpable one.

;; If you add/remove Lisp files to be loaded here, consider the
;; following issues:

;; i) Any file loaded on any platform should appear in $lisp in src/lisp.mk.
;; Use the .el or .elc version as appropriate.

;; This ensures both that the Lisp files are compiled (if necessary)
;; before the emacs executable is dumped, and that they are passed to
;; make-docfile.  (Any that are not processed for DOC will not have
;; doc strings in the dumped Emacs.)  Because of this:

;; ii) If the file is loaded uncompiled, it should (where possible)
;; obey the doc-string conventions expected by make-docfile.

;;; Code:

;; DEUCE: deuce.main loads this file and prepend src/bootstrap-emacs to the command line to emulate CANNOT_DUMP.
;;        Unlike in Emacs, this file is loaded every time Deuce starts, but the referenced Emacs Lisp will normally be AOT compiled.
;;        Deuce compiles Emacs Lisp as it loads it. This file takes more than 10 minutes to load the first time.
;;        Just because things here load and compile doens't mean they work, they depend on the primitives under deuce.emacs.
;;        These, which are normal Clojure, gets AOT compiled via lein uberjar.

;; Add subdirectories to the load-path for files that might get
;; autoloaded when bootstrapping.
(if (or (equal (nth 3 command-line-args) "bootstrap")
	(equal (nth 4 command-line-args) "bootstrap")
	(equal (nth 3 command-line-args) "unidata-gen.el")
	(equal (nth 4 command-line-args) "unidata-gen-files")
	;; In case CANNOT_DUMP.
	(string-match "src/bootstrap-emacs" (nth 0 command-line-args)))
    (let ((dir (car load-path)))
      ;; We'll probably overflow the pure space.
      (setq purify-flag nil)
      (setq load-path (list dir
			    (expand-file-name "emacs-lisp" dir)
			    (expand-file-name "language" dir)
			    (expand-file-name "international" dir)
			    (expand-file-name "textmodes" dir)))))

(if (eq t purify-flag)
    ;; Hash consing saved around 11% of pure space in my tests.
    (setq purify-flag (make-hash-table :test 'equal)))

(message "Using load-path %s" load-path)

(if (or (member (nth 3 command-line-args) '("dump" "bootstrap"))
	(member (nth 4 command-line-args) '("dump" "bootstrap")))
    ;; To reduce the size of dumped Emacs, we avoid making huge
    ;; char-tables.
    (setq inhibit-load-charset-map t))

;; We don't want to have any undo records in the dumped Emacs.
(set-buffer "*scratch*")
(setq buffer-undo-list t)

;; DEUCE: Handles inlining, won't be used, but other parts references its macros.
;;        24.3 Note: Defines defmacro using defalias + lambda and then defun using it here.
;;                   Handles doc strings etc, so we only need to support 'lambda'.
;;                   Probably nice in theory, but will be messier to bootstrap as Deuce core will require byte-run.el to work.
;;                   If doing this, we should consider using real backquote.el as well.
(load "emacs-lisp/byte-run")
;; DEUCE: backquote is used by lread.c, and not used in Deuce to avoid having the reader depending on Emacs Lisp.
;;        Instead I use the internal SyntaxQuoteReader from Clojure - may revisit.
(load "emacs-lisp/backquote")
;; DEUCE: Lisp helpers/setup, some things, like dolist etc, are replaced by cl.el
(load "subr")

;; Do it after subr, since both after-load-functions and add-hook are
;; implemented in subr.el.
(add-hook 'after-load-functions (lambda (f) (garbage-collect)))

;; We specify .el in case someone compiled version.el by mistake.
(load "version.el")

;; DEUCE: Support for defining widgets as used by customize. Not used for hyperlinks etc, see button below.
;;        No real intention of supporting it (yet), but custom assumes its there.
(load "widget")
;; DEUCE: custom subsystem, not strictly necessary, but other things depend on it being there.
(load "custom")
;; DEUCE: Yes/No prompt.
(load "emacs-lisp/map-ynp")
;; DEUCE: Adds custom support for built in variables.
(load "cus-start")
;; DEUCE: MULE defines and deals with character encodings, won't be used, but some fns might be needed.
;;        We're runningw without MULE for now, may revisit.
;; (load "international/mule")
;; (load "international/mule-conf")
;; DEUCE: unix environment helpers, causes cl.el to be loaded.
(load "env")

;; DEUCE: Support for loading files with different "formats", not part of Mule.
(load "format")

;; DEUCE: All basic editor key bindings are setup here - many refer to fns loaded later on.
(load "bindings")
;; DEUCE: Defines C-x 2, C-x o etc.
(load "window")  ; Needed here for `replace-buffer-in-windows'.
(setq load-source-file-function 'load-with-code-conversion)
;; DEUCE: Defines C-x C-f, C-x C-s etc.
(load "files")

;; DEUCE: custom extensions for faces
(load "cus-face")
;; DEUCE: tty-run-terminal-initialization is defined here, uses TERM to load term/xterm.el (for example), we might add our own / sidestep.
(load "faces")  ; after here, `defface' may be used.

;; DEUCE: button provides hyperlinks even in keyboard mode, needed for the startup screen.
(load "button")
;; DEUCE: Actual startup of Emacs, parses command lines, opens the first frame and displays welcome and *scratch*
;;        Loads init.el, but we'll surpress that for now. We want to support emacs -nw -q
;;        Also loads subdirs.el which extends the load-path. Tries to load leim for international input, but should not be needed.
(load "startup")

;; DEUCE: At this point normal-top-level will be available.
;;        Calling it (see end of this file) should start Emacs and clojure-lanterna can be intialized.
;;        I want to drive out the boot backwards based on what's needed at this point - not mimic the C.
;;        Actual details of the init of different subsystems are a mix of C (hence .clj) and Emacs Lisp.
;;        See emacs.c for the lowlevel init, also: frame.c and window.c.
;;        The GNU Emacs buffer will be shown: "Welcome to GNU Emacs, one component of the GNU/Linux operating system."

;; DEUCE: Suffixed .el files don't get .class files.
(condition-case nil
    ;; Don't get confused if someone compiled this by mistake.
    (load "loaddefs.el")
  ;; In case loaddefs hasn't been generated yet.
  (file-error (load "ldefs-boot.el")))

;; DEUCE: minibuffer implements completion, the actual minibuffer is (not yet) in minibuf.clj
;;        Blows up when autoloading/compiling pcase.el - can be worked around by C-c C-k in slime:
;;        Swank uses clojure.core/load-file which is Compiler/loadFile to do this.
;;        require etc. use load-lib which uses RT/load which deals with .class files etc.
;;        Some difference causes a InstantiationException in completion-at-point (which autoloads pcase).
;;        Deuce extension to catch Java exceptions, should be condition-case, but this is easier.
;;        Both these files use: -*- lexical-binding: t -*- which we don't adhere to yet.
(when (catch 'java.lang.ReflectiveOperationException
        (load "minibuffer" nil t))
  (load "pcase.el" nil t))
(load "minibuffer")
;; DEUCE: abbrev mode, referenced by simple below (to turn it off at times)
(load "abbrev")         ;lisp-mode.el and simple.el use define-abbrev-table.
;; DEUCE: Massive support file for Emacs, adds completion, paren matching, line movement and various things.
(load "simple")

(load "help")

(load "jka-cmpr-hook")
(load "epa-hook")
;; Any Emacs Lisp source file (*.el) loaded here after can contain
;; multilingual text.
;; (load "international/mule-cmds")
(load "case-table")
;; This file doesn't exist when building a development version of Emacs
;; from the repository.  It is generated just after temacs is built.
;; (load "international/charprop.el" t)
;; DEUCE: NPE in case-table/set-case-syntax-pair
;;        Requires better emulation of entire chartab subsystem, will revist.
;; (load "international/characters")
(load "composite")

;; DEUCE: Lanugage support to be revisited, one language must be defined.
;; Load language-specific files.
;; (load "language/chinese")
;; (load "language/cyrillic")
;; (load "language/indian")
;; (load "language/sinhala")
;; (load "language/english")
;; (load "language/ethiopic")
;; (load "language/european")
;; (load "language/czech")
;; (load "language/slovak")
;; (load "language/romanian")
;; (load "language/greek")
;; (load "language/hebrew")
;; (load "language/japanese")
;; (load "language/korean")
;; (load "language/lao")
;; (load "language/tai-viet")
;; (load "language/thai")
;; (load "language/tibetan")
;; (load "language/vietnamese")
;; (load "language/misc-lang")
;; (load "language/utf-8-lang")
;; (load "language/georgian")
;; (load "language/khmer")
;; (load "language/burmese")
;; (load "language/cham")

(load "indent")
(load "frame")
;; DEUCE: Referenced from startup.
;;        The generated init class gets invalid method code length, but loading the .clj itself works:
;;        rm target/classes/term/tty_colors__init.class
;; (load "term/tty-colors")
;; DEUCE: Hack to not compile tty-colors for now, see above.
(load "term/tty-colors.el")
(load "font-core")
;; facemenu must be loaded before font-lock, because `facemenu-keymap'
;; needs to be defined when font-lock is loaded.
(load "facemenu")
;; DEUCE: (void-variable method) cl-macs/cl-setf-do-modify refers to cl-setf-simple-store-p before its defined inside a let
;;        Hack to work around this is in deuce.emacs by predefining cl-setf-simple-store-p.
(load "emacs-lisp/syntax")
;; DEUCE: Has hack in deuce.emacs for regexp-opt/regexp-opt-group to be predefined, as its used inside let, see above.
(load "font-lock")
(load "jit-lock")

(if (fboundp 'track-mouse)
    (progn
      (load "mouse")
      (and (boundp 'x-toolkit-scroll-bars)
	   (load "scroll-bar"))
      (load "select")))
;; DEUCE: InstantiationException in timer-activate, comes from 'with-no-warnings with 'cl-struct-setf-expander
;;        Hack to get around this by pre-defining 'last' in deuce.emacs
;;        In Clojure 1.8.0 this fails with No setf-method known for timer--high-seconds.
(load "emacs-lisp/timer")
(load "isearch")
(load "rfn-eshadow")

(load "menu-bar")
(load "paths.el")  ;Don't get confused if someone compiled paths by mistake.
(load "emacs-lisp/lisp")
(load "textmodes/page")
;; DEUCE: Sanity check against http://www.gnu.org/software/emacs/manual/html_node/cl/Structures.html (see emacs-lisp/timer above).
;;        There's a totally wonderful error that comes from this guy occasionally when runnng as uberjar:
;;        ClassCastException: clojure.lang.PersistentList cannot be cast to clojure.lang.Named
;;        Some cl argument list expansion goes wrong and pass in a list inside the argument list.
;;        The root cause seems to be define-compiler-macro. Stubbing it out for now, should be "safe".
(load "register")
(load "textmodes/paragraphs")
;; DEUCE: Hack in deuce.emacs to deal with (void-variable sorted-strings) -  same delayed-eval issue as font-lock above.
(load "emacs-lisp/lisp-mode")
(load "textmodes/text-mode")
(load "textmodes/fill")

(load "replace")
;; DEUCE: Hack to not compile buff-menu for now, its buffer-menu-other-window and Buffer-menu-other-window clashes on Mac.
(load "buff-menu.el")

(if (fboundp 'x-create-frame)
    (progn
      (load "fringe")
      (load "image")
      (load "international/fontset")
      (load "dnd")
      (load "tool-bar")))

(if (featurep 'dynamic-setting)
    (load "dynamic-setting"))

(if (featurep 'x)
    (progn
      (load "x-dnd")
      (load "term/common-win")
      (load "term/x-win")))

(if (eq system-type 'windows-nt)
    (progn
      (load "w32-vars")
      (load "term/common-win")
      (load "term/w32-win")
      (load "ls-lisp")
      (load "disp-table")
      (load "dos-w32")
      (load "w32-fns")))
(if (eq system-type 'ms-dos)
    (progn
      (load "dos-w32")
      (load "dos-fns")
      (load "dos-vars")
      ;; Don't load term/common-win: it isn't appropriate for the `pc'
      ;; ``window system'', which generally behaves like a terminal.
      (load "term/pc-win")
      (load "ls-lisp")
      (load "disp-table"))) ; needed to setup ibm-pc char set, see internal.el
(if (featurep 'ns)
    (progn
      (load "term/common-win")
      (load "term/ns-win")))
(if (fboundp 'x-create-frame)
    ;; Do it after loading term/foo-win.el since the value of the
    ;; mouse-wheel-*-event vars depends on those files being loaded or not.
    (load "mwheel"))

;; Preload some constants and floating point functions.
(load "emacs-lisp/float-sup")

(load "vc/vc-hooks")
(load "vc/ediff-hook")
(if (fboundp 'x-show-tip) (load "tooltip"))

;; DEUCE: Relevant parts of loadup done. A vanilla emacs -nw -q.

;; DEUCE: site-load, not supported, will revisit.
;; ;If you want additional libraries to be preloaded and their
;; ;doc strings kept in the DOC file rather than in core,
;; ;you may load them with a "site-load.el" file.
;; ;But you must also cause them to be scanned when the DOC file
;; ;is generated.
;; ;For other systems, you must edit ../src/Makefile.in.
;; (load "site-load" t)

;; DEUCE: build version number code, not used by Deuce.
;; Determine which last version number to use
;; based on the executables that now exist.
;; (if (and (or (equal (nth 3 command-line-args) "dump")
;; 	     (equal (nth 4 command-line-args) "dump"))
;; 	 (not (eq system-type 'ms-dos)))
;;     (let* ((base (concat "emacs-" emacs-version "."))
;; 	   (files (file-name-all-completions base default-directory))
;; 	   (versions (mapcar (function (lambda (name)
;; 					 (string-to-number (substring name (length base)))))
;; 			     files)))
;;       ;; `emacs-version' is a constant, so we shouldn't change it with `setq'.
;;       (defconst emacs-version
;; 	(format "%s.%d"
;; 		emacs-version (if versions (1+ (apply 'max versions)) 1)))))


;; DEUCE: doc strings are embedded inside Clojure, not needed
;; (message "Finding pointers to doc strings...")
;; (if (or (equal (nth 3 command-line-args) "dump")
;; 	(equal (nth 4 command-line-args) "dump"))
;;     (let ((name emacs-version))
;;       (while (string-match "[^-+_.a-zA-Z0-9]+" name)
;; 	(setq name (concat (downcase (substring name 0 (match-beginning 0)))
;; 			   "-"
;; 			   (substring name (match-end 0)))))
;;       (if (memq system-type '(ms-dos windows-nt))
;; 	  (setq name (expand-file-name
;; 		      (if (fboundp 'x-create-frame) "DOC-X" "DOC") "../etc"))
;; 	(setq name (concat (expand-file-name "../etc/DOC-") name))
;; 	(if (file-exists-p name)
;; 	    (delete-file name))
;; 	(copy-file (expand-file-name "../etc/DOC") name t))
;;       (Snarf-documentation (file-name-nondirectory name)))
;;     (condition-case nil
;; 	(Snarf-documentation "DOC")
;;       (error nil)))
;; (message "Finding pointers to doc strings...done")

;; DEUCE: site-init, not supported
;; ;; Note: You can cause additional libraries to be preloaded
;; ;; by writing a site-init.el that loads them.
;; ;; See also "site-load" above.
;; (load "site-init" t)

(setq current-load-list nil)

;; We keep the load-history data in PURE space.
;; Make sure that the spine of the list is not in pure space because it can
;; be destructively mutated in lread.c:build_load_history.
(setq load-history (mapcar 'purecopy load-history))

(set-buffer-modified-p nil)

;; reset the load-path.  See lread.c:init_lread why.
(if (or (equal (nth 3 command-line-args) "bootstrap")
	(equal (nth 4 command-line-args) "bootstrap"))
    (setcdr load-path nil))
;; DEUCE: Resetting load-path to (""), subdirs.el will rebuild it.
(setcdr load-path nil)

(remove-hook 'after-load-functions (lambda (f) (garbage-collect)))

(setq inhibit-load-charset-map nil)
(clear-charset-maps)
(garbage-collect)

;; At this point, we're ready to resume undo recording for scratch.
(buffer-enable-undo "*scratch*")

;; Avoid error if user loads some more libraries now and make sure the
;; hash-consing hash table is GC'd.
(setq purify-flag nil)

(if (null (garbage-collect))
    (setq pure-space-overflow t))

;; DEUCE: This writes out and dumps the actual emacs binary, we will use lein uberjar with AOT.
;; (if (or (member (nth 3 command-line-args) '("dump" "bootstrap"))
;; 	(member (nth 4 command-line-args) '("dump" "bootstrap")))
;;     (progn
;;       (if (memq system-type '(ms-dos windows-nt cygwin))
;;           (message "Dumping under the name emacs")
;;         (message "Dumping under the name emacs"))
;;       (condition-case ()
;; 	  (delete-file "emacs")
;; 	(file-error nil))
;;       ;; We used to dump under the name xemacs, but that occasionally
;;       ;; confused people installing Emacs (they'd install the file
;;       ;; under the name `xemacs'), and it's inconsistent with every
;;       ;; other GNU program's build process.
;;       (dump-emacs "emacs" "temacs")
;;       (message "%d pure bytes used" pure-bytes-used)
;;       ;; Recompute NAME now, so that it isn't set when we dump.
;;       (if (not (or (memq system-type '(ms-dos windows-nt))
;;                    ;; Don't bother adding another name if we're just
;;                    ;; building bootstrap-emacs.
;;                    (equal (nth 3 command-line-args) "bootstrap")
;;                    (equal (nth 4 command-line-args) "bootstrap")))
;; 	  (let ((name (concat "emacs-" emacs-version)))
;; 	    (while (string-match "[^-+_.a-zA-Z0-9]+" name)
;; 	      (setq name (concat (downcase (substring name 0 (match-beginning 0)))
;; 				 "-"
;; 				 (substring name (match-end 0)))))
;;             (message "Adding name %s" name)
;; 	    (add-name-to-file "emacs" name t)))
;;       (kill-emacs)))

;; For machines with CANNOT_DUMP defined in config.h,
;; this file must be loaded each time Emacs is run.
;; So run the startup code now.  First, remove `-l loadup' from args.

(if (and (equal (nth 1 command-line-args) "-l")
	 (equal (nth 2 command-line-args) "loadup"))
    (setcdr command-line-args (nthcdr 3 command-line-args)))

;; DEUCE: Starts Emacs. This entire file will be AOT compiled together with referenced Emacs Lisp.
;;        deuce-loadup.el is loaded in deuce.main. Further C init goes into deuce.emacs.
(eval top-level)


;; Local Variables:
;; no-byte-compile: t
;; no-update-autoloads: t
;; End:

;;; loadup.el ends here
