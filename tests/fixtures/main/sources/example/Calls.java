package example;

public final class Calls {
    private static final String[] directChunks = {
        "\ufff6\uff9b\uff96\uff8d\uff9a\uff9c\uff8b\uffd2\uff90\uff94"
    };

    public String direct() {
        return DeobfuscatorHelper.getString(-4008636143L, directChunks);
    }

    public String wrapper() {
        return Deobfuscator$example$module.getString(-3722304990L);
    }
}
