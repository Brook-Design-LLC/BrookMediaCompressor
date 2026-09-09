package com.brook.tools.mediacompress.win;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Structure;
import com.sun.jna.WString;

public class COMDLG_FILTERSPEC extends Structure {
    public WString pszName;
    public WString pszSpec;

    public COMDLG_FILTERSPEC() {
    }

    public COMDLG_FILTERSPEC(String name, String spec) {
        pszName = new WString(name);
        pszSpec = new WString(spec);
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("pszName", "pszSpec");
    }
}
