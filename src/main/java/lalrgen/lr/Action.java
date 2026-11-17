package lalrgen.lr;

/** An entry of the ACTION table. A missing entry (null) means "syntax error". */
public sealed interface Action permits Action.Shift, Action.Reduce, Action.Accept, Action.Error {

    record Shift(int state) implements Action {
        @Override
        public String toString() {
            return "s" + state;
        }
    }

    record Reduce(int production) implements Action {
        @Override
        public String toString() {
            return "r" + production;
        }
    }

    record Accept() implements Action {
        @Override
        public String toString() {
            return "acc";
        }
    }

    /** An explicit error entry, created by %nonassoc (e.g. "a < b < c"). */
    record Error() implements Action {
        @Override
        public String toString() {
            return "err";
        }
    }

    Accept ACCEPT = new Accept();
    Error ERROR = new Error();
}
