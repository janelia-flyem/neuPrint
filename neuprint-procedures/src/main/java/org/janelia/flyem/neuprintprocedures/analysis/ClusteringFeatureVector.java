package org.janelia.flyem.neuprintprocedures.analysis;

import com.google.gson.Gson;

import java.util.Set;

public class ClusteringFeatureVector {

    private Long bodyId;
    private long[] inputFeatureVector;
    private long[] outputFeatureVector;

    private final static Gson gson = new Gson();

    ClusteringFeatureVector(Long bodyId, long[] inputFeatureVector, long[] outputFeatureVector) {
        this.bodyId = bodyId;
        this.inputFeatureVector = inputFeatureVector;
        this.outputFeatureVector = outputFeatureVector;
    }

    static String getClusteringFeatureVectorSetJson(Set<ClusteringFeatureVector> clusteringFeatureVectors) {
        return gson.toJson(clusteringFeatureVectors);
    }

    public Long getBodyId() {
        return bodyId;
    }

    long[] getInputFeatureVector() {
        return inputFeatureVector;
    }

    public long[] getOutputFeatureVector() {
        return outputFeatureVector;
    }

    @Override
    public String toString() {
        return gson.toJson(this);
    }

    @Override
    public boolean equals(Object o) {
        boolean isEqual = false;
        if (this == o) {
            isEqual = true;
        } else if (o instanceof ClusteringFeatureVector) {
            final ClusteringFeatureVector that = (ClusteringFeatureVector) o;
            isEqual = this.bodyId.equals(that.bodyId) ;
        }
        return isEqual;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + this.bodyId.hashCode();
        return result;
    }
}
