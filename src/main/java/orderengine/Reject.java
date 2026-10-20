package orderengine;

public enum Reject {
    NONE("none"),
    ZERO_QUANTITY("zero quantity"),
    DUPLICATE_ID("duplicate order id"),
    NO_LIQUIDITY("no liquidity for market order"),   // market order with an empty opposite book
    FILL_OR_KILL("fill-or-kill could not be filled"), // FOK that could not be filled completely
    UNKNOWN_ORDER("unknown order id");                // cancel/amend for an id that is not resting

    private final String text;

    Reject(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return text;
    }
}
