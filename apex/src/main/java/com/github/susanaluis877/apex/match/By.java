package com.github.susanaluis877.apex.match;

/**
 * {@link Selector} 的入口，例如 {@code By.id("submit").enabled(true)}。
 */
public final class By {

    private By() {
    }

    public static Selector text(String value) {
        return new Selector().text(value);
    }

    public static Selector textContains(String value) {
        return new Selector().textContains(value);
    }

    public static Selector textMatches(String regex) {
        return new Selector().textMatches(regex);
    }

    public static Selector desc(String value) {
        return new Selector().desc(value);
    }

    public static Selector descContains(String value) {
        return new Selector().descContains(value);
    }

    public static Selector descMatches(String regex) {
        return new Selector().descMatches(regex);
    }

    public static Selector id(String value) {
        return new Selector().id(value);
    }

    public static Selector className(String value) {
        return new Selector().className(value);
    }

    public static Selector clickable(boolean value) {
        return new Selector().clickable(value);
    }

    public static Selector enabled(boolean value) {
        return new Selector().enabled(value);
    }

    public static Selector checked(boolean value) {
        return new Selector().checked(value);
    }

    public static Selector selected(boolean value) {
        return new Selector().selected(value);
    }

    public static Selector scrollable(boolean value) {
        return new Selector().scrollable(value);
    }

    public static Selector stateDesc(String value) {
        return new Selector().stateDesc(value);
    }

    public static Selector stateDescContains(String value) {
        return new Selector().stateDescContains(value);
    }

    public static Selector progressAtLeast(double ratio) {
        return new Selector().progressAtLeast(ratio);
    }

    public static Selector progressAtMost(double ratio) {
        return new Selector().progressAtMost(ratio);
    }

    public static Selector hasDescendant(Selector... selectors) {
        return new Selector().hasDescendant(selectors);
    }

    public static Selector inside(Selector selector) {
        return new Selector().inside(selector);
    }
}
