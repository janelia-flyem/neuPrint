package org.janelia.flyem.neuprinter.model;

import java.util.HashMap;
import java.util.Map;

/**
 * A class representing automatically generated names for Neuron nodes
 * within a neuprint neo4j database. An AutoName is in the format
 * A-B-id, where A is the ROI (region of interest) in which the
 * given neuron has the most inputs (postsynaptic densities), B is the
 * the ROI in which the neuron has the most otuputs (presynaptic densities),
 * and id is an integer that renders each AutoName unique.
 */
public class AutoName {

    private static Map<String,Integer> AUTO_NAME_ID_COUNT = new HashMap<>();
    private String maxInputRoi;
    private String maxOutputRoi;
    private int id;
    private long bodyId;

    /**
     * Class constructor.
     *
     * @param maxInputRoi max input roi name
     * @param maxOutputRoi max output roi name
     * @param bodyId Neuron's bodyId
     */
    public AutoName(String maxInputRoi, String maxOutputRoi, long bodyId) {
        this.maxInputRoi = maxInputRoi;
        this.maxOutputRoi = maxOutputRoi;
        this.bodyId = bodyId;
        String nameKey = maxInputRoi + ":" + maxInputRoi;
        if (AUTO_NAME_ID_COUNT.get(nameKey)==null) {
            AUTO_NAME_ID_COUNT.put(nameKey, 0);
            this.id = 0;
        } else {
            Integer currentCount = AUTO_NAME_ID_COUNT.get(nameKey);
            currentCount++;
            this.id = currentCount;
            AUTO_NAME_ID_COUNT.put(nameKey, currentCount);
        }
    }

    /**
     *
     * @return automatically generated name for Neuron
     */
    public String getAutoName() {
        return this.maxInputRoi.toUpperCase() + "-" + this.maxOutputRoi.toUpperCase() + "_" + id;
    }

    /**
     *
     * @return bodyId of Neuron assigned the autoname
     */
    public long getBodyId() {
        return bodyId;
    }

    @Override
    public String toString() {
        return "bodyId: " + this.bodyId + " " + getAutoName();
    }
}
