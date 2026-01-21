package com.monadvsim.app.models;

import java.util.Map;
import java.util.HashMap;

public class CRSService {

    public static final String DEFAULT_CRS = "EPSG:4326"; // WGS84
    public static final String MERCATOR_CRS = "EPSG:3857";

    public static boolean isGeographic(String epsgCode) {
        return epsgCode.equals(DEFAULT_CRS)
                || epsgCode.contains("4326")
                || epsgCode.toLowerCase().contains("wgs84");
    }

    public static boolean isProjected(String epsgCode) {
        return epsgCode.equals(MERCATOR_CRS)
                || epsgCode.contains("3857")
                || epsgCode.toLowerCase().contains("mercator");
    }

    public static String getHumanReadableName(String epsgCode) {
        switch (epsgCode) {
            case DEFAULT_CRS:
                return "WGS84 (Lat/Lon)";
            case MERCATOR_CRS:
                return "Web Mercator";
            case "EPSG:32633":
                return "UTM Zone 33N";
            default:
                return epsgCode;
        }
    }
}
