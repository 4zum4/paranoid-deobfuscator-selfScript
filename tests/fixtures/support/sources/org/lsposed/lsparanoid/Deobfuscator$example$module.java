package org.lsposed.lsparanoid;

public final class Deobfuscator$example$module {
    private static final String[] chunks = {
        "\ufff5\uff88\uda58\uff9e\uff8f\uff8f\uff9a\uff8d\uffd2\uff90\uff94"
    };

    public static String getString(long id) {
        return DeobfuscatorHelper.getString(id, chunks);
    }
}
