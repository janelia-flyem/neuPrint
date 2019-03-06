package org.janelia.flyem.neuprintloadprocedures.procedures;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools;
import org.janelia.flyem.neuprintloadprocedures.Location;
import org.janelia.flyem.neuprintloadprocedures.model.RoiInfoWithHighPrecisionCounts;
import org.janelia.flyem.neuprintloadprocedures.model.SynapseCounterWithHighPrecisionCounts;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.CONFIDENCE;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.CONNECTS_TO;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.FROM;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.NAME;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.POST;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.PRE;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.ROI_INFO;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.SIZE;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.SOMA_LOCATION;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.SOMA_RADIUS;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.STATUS;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.TIME_STAMP;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.TO;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.TYPE;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.WEIGHT;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.WEIGHT_HP;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.createSegment;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.getSegment;
import static org.janelia.flyem.neuprintloadprocedures.GraphTraversalTools.getSynapseRois;

public class LoadingProcedures {
    @Context
    public GraphDatabaseService dbService;

    @Context
    public Log log;

    public static final Type ROI_INFO_WITH_HP_TYPE = new TypeToken<Map<String, SynapseCounterWithHighPrecisionCounts>>() {
    }.getType();

    @Procedure(value = "loader.setConnectionSetRoiInfoAndWeightHP", mode = Mode.WRITE)
    @Description("loader.setConnectionSetRoiInfoAndWeightHP : Add roiInfo property to ConnectionSet node.")
    public void setConnectionSetRoiInfoAndWeightHP(@Name("preBodyId") final Long preBodyId,
                                                   @Name("postBodyId") final Long postBodyId,
                                                   @Name("datasetLabel") final String datasetLabel,
                                                   @Name("preHPThreshold") final Double preHPThreshold,
                                                   @Name("postHPThreshold") final Double postHPThreshold) {

        log.info("loader.setConnectionSetRoiInfoAndWeightHP: entry");

        try {

            if (preBodyId == null || postBodyId == null || datasetLabel == null || preHPThreshold == null || postHPThreshold == null) {
                log.error("loader.setConnectionSetRoiInfoAndWeightHP: Missing input arguments.");
                throw new RuntimeException("loader.setConnectionSetRoiInfoAndWeightHP: Missing input arguments.");
            }

            Node connectionSet = GraphTraversalTools.getConnectionSetNode(dbService, preBodyId, postBodyId, datasetLabel);

            if (connectionSet == null) {
                log.error(String.format("loader.setConnectionSetRoiInfoAndWeightHP: ConnectionSet does not exist: %d to %d in dataset %s.", preBodyId, postBodyId, datasetLabel));
                throw new RuntimeException(String.format("loader.setConnectionSetRoiInfoAndWeightHP: ConnectionSet does not exist: %d to %d in dataset %s.", preBodyId, postBodyId, datasetLabel));
            }

            // get all synapses on connection set
            Set<Node> synapsesForConnectionSet = GraphTraversalTools.getSynapsesForConnectionSet(connectionSet);

            int postHP = setConnectionSetRoiInfoAndGetWeightHP(synapsesForConnectionSet, connectionSet, preHPThreshold, postHPThreshold);

            // add postHP to ConnectsTo
            addPostHPToConnectsTo(connectionSet, postHP);

        } catch (Exception e) {
            log.info(String.format("loader.setConnectionSetRoiInfoAndWeightHP: Error adding roiInfo: %s, pre body ID: %d, post body ID: %d", e, preBodyId, postBodyId));
            throw new RuntimeException(String.format("loader.setConnectionSetRoiInfoAndWeightHP: Error adding roiInfo: %s, pre body ID: %d, post body ID: %d", e, preBodyId, postBodyId));
        }

        log.info("loader.setConnectionSetRoiInfoAndWeightHP: exit");

    }

    public static int setConnectionSetRoiInfoAndGetWeightHP(Set<Node> synapsesForConnectionSet, Node connectionSet, Double preHPThreshold, Double postHPThreshold) {

        Object[] roiInfoAndPostHP = getRoiInfoForConnectionSet(synapsesForConnectionSet, preHPThreshold, postHPThreshold);
        RoiInfoWithHighPrecisionCounts roiInfo = (RoiInfoWithHighPrecisionCounts) roiInfoAndPostHP[0];
        int postHP = (int) roiInfoAndPostHP[1];

        // add to connection set node
        connectionSet.setProperty(ROI_INFO, roiInfo.getAsJsonString());

        return postHP;

    }

    private static Object[] getRoiInfoForConnectionSet(Set<Node> synapsesForConnectionSet, Double preHPThreshold, Double postHPThreshold) {

        // for each pre/post add to count and check confidence to add to hp count
        RoiInfoWithHighPrecisionCounts roiInfo = new RoiInfoWithHighPrecisionCounts();

        // total postHP count for weightHP on ConnectsTo
        int postHP = 0;

        for (Node synapse : synapsesForConnectionSet) {
            String type;
            Double confidence;
            if (synapse.hasProperty(TYPE)) {
                type = (String) synapse.getProperty(TYPE);
            } else {
                break;
            }
            if (synapse.hasProperty(CONFIDENCE)) {
                confidence = (Double) synapse.getProperty(CONFIDENCE);
            } else {
                confidence = null;
            }
            Set<String> synapseRois = getSynapseRois(synapse);
            if (type.equals(PRE) && confidence != null && confidence > preHPThreshold) {
                for (String roi : synapseRois) {
                    roiInfo.incrementPreForRoi(roi);
                    roiInfo.incrementPreHPForRoi(roi);
                }
            } else if (type.equals(PRE)) {
                for (String roi : synapseRois) {
                    roiInfo.incrementPreForRoi(roi);
                }
            } else if (type.equals(POST) && confidence != null && confidence > postHPThreshold) {
                postHP++;
                for (String roi : synapseRois) {
                    roiInfo.incrementPostForRoi(roi);
                    roiInfo.incrementPostHPForRoi(roi);
                }
            } else if (type.equals(POST)) {
                for (String roi : synapseRois) {
                    roiInfo.incrementPostForRoi(roi);
                }
            }
        }

        return new Object[]{roiInfo, postHP};
    }

    public static String addSynapseToRoiInfoWithHP(String roiInfoString, String roi, String synapseType, Double synapseConfidence, Double preHPThreshold, Double postHPThreshold) {
        Gson gson = new Gson();
        Map<String, SynapseCounterWithHighPrecisionCounts> roiInfoMap = gson.fromJson(roiInfoString, ROI_INFO_WITH_HP_TYPE);
        RoiInfoWithHighPrecisionCounts roiInfo = new RoiInfoWithHighPrecisionCounts(roiInfoMap);

        if (synapseType.equals(PRE) && synapseConfidence != null && synapseConfidence > preHPThreshold) {
            roiInfo.incrementPreForRoi(roi);
            roiInfo.incrementPreHPForRoi(roi);
        } else if (synapseType.equals(PRE)) {
            roiInfo.incrementPreForRoi(roi);
        } else if (synapseType.equals(POST) && synapseConfidence != null && synapseConfidence > postHPThreshold) {
            roiInfo.incrementPostForRoi(roi);
            roiInfo.incrementPostHPForRoi(roi);
        } else if (synapseType.equals(POST)) {
            roiInfo.incrementPostForRoi(roi);
        }

        return roiInfo.getAsJsonString();

    }

    public static String removeSynapseFromRoiInfoWithHP(String roiInfoString, String roi, String synapseType, Double synapseConfidence, Double preHPThreshold, Double postHPThreshold) {
        Gson gson = new Gson();
        Map<String, SynapseCounterWithHighPrecisionCounts> roiInfoMap = gson.fromJson(roiInfoString, ROI_INFO_WITH_HP_TYPE);
        RoiInfoWithHighPrecisionCounts roiInfo = new RoiInfoWithHighPrecisionCounts(roiInfoMap);

        if (synapseType.equals(PRE) && synapseConfidence != null && synapseConfidence > preHPThreshold) {
            roiInfo.decrementPreForRoi(roi);
            roiInfo.decrementPreHPForRoi(roi);
        } else if (synapseType.equals(PRE)) {
            roiInfo.decrementPreForRoi(roi);
        } else if (synapseType.equals(POST) && synapseConfidence != null && synapseConfidence > postHPThreshold) {
            roiInfo.decrementPostForRoi(roi);
            roiInfo.decrementPostHPForRoi(roi);
        } else if (synapseType.equals(POST)) {
            roiInfo.decrementPostForRoi(roi);
        }

        return roiInfo.getAsJsonString();

    }

    @Procedure(value = "loader.addSegment", mode = Mode.WRITE)
    @Description("loader.addSegment : Add Segment nodes to database.")
    public void addSegment(@Name("bodyId") final Long bodyId,
                           @Name("datasetLabel") final String datasetLabel,
                           @Name("name") final String name,
                           @Name("type") final String type,
                           @Name("status") final String status,
                           @Name("size") final Long size,
                           @Name("somaLocation") final List<Long> somaLocation,
                           @Name("somaRadius") final Double somaRadius,
                           @Name("rois") final List<String> rois,
                           @Name("timeStamp") final LocalDateTime timeStamp) {

        log.info("loader.addSegment: entry");

        if (bodyId == null) {
            log.error("loader.addSegment: No body ID provided.");
            throw new RuntimeException("loader.addSegment: No body ID provided.");
        } else if (datasetLabel == null) {
            log.error("loader.addSegment: No dataset name provided.");
            throw new RuntimeException("loader.addSegment: No dataset name provided.");
        } else if (timeStamp == null) {
            log.error("loader.addSegment: No time stamp provided.");
            throw new RuntimeException("loader.addSegment: No time stamp provided.");
        }

        // check that neuron doesn't already exist
        Node existingNeuron = getSegment(dbService, bodyId, datasetLabel);
        if (existingNeuron != null) {
            log.warn("loader.addSegment: Body ID already exists for this dataset. Will not overwrite.");
        } else {
            Node newSegment;
            try {
                newSegment = createSegment(dbService, bodyId, datasetLabel);
            } catch (Exception e) {
                log.error("loader.addSegment: Failed to create new segment: " + e);
                throw new RuntimeException();
            }
            if (name != null) {
                newSegment.setProperty(NAME, name);
            }
            if (type != null) {
                newSegment.setProperty(TYPE, type);
            }
            if (status != null) {
                newSegment.setProperty(STATUS, status);
            }
            if (size != null) {
                newSegment.setProperty(SIZE, size);
            }

            if (somaLocation != null) {
                Point somaLocationPoint = new Location(somaLocation.get(0), somaLocation.get(1), somaLocation.get(2));
                newSegment.setProperty(SOMA_LOCATION, somaLocationPoint);
            }
            if (somaRadius != null) {
                newSegment.setProperty(SOMA_RADIUS, somaRadius);
            }

            if (rois != null) {
                for (String roi : rois) {
                    newSegment.setProperty(roi, true);
                }
            }

            newSegment.setProperty(TIME_STAMP, timeStamp);
        }

        log.info("loader.addSegment: exit");

    }

    @Procedure(value = "loader.addConnectsTo", mode = Mode.WRITE)
    @Description("loader.addConnectsTo : Add ConnectsTo relationship between bodies.")
    public void addConnectsTo(@Name("preBodyId") final Long preBodyId,
                              @Name("postBodyId") final Long postBodyId,
                              @Name("weight") final Long weight,
                              @Name("preBodyPreCount") final Long preBodyPreCount,
                              @Name("preBodyPostCount") final Long preBodyPostCount,
                              @Name("preBodyRoiInfo") final String preBodyRoiInfo,
                              @Name("datasetLabel") final String datasetLabel,
                              @Name("timeStamp") final LocalDateTime timeStamp) {

        log.info("loader.addConnectsTo: entry");

        if (preBodyId == null) {
            log.error("loader.addConnectsTo: No pre body ID provided.");
            throw new RuntimeException("loader.addConnectsTo: No pre body ID provided.");
        } else if (postBodyId == null) {
            log.error("loader.addConnectsTo: No post body ID provided.");
            throw new RuntimeException("loader.addConnectsTo: No post body ID provided.");
        } else if (weight == null) {
            log.error("loader.addConnectsTo: No weight provided.");
            throw new RuntimeException("loader.addConnectsTo: No weight provided.");
        } else if (preBodyPreCount == null) {
            log.error("loader.addConnectsTo: No pre count for pre body provided.");
            throw new RuntimeException("loader.addConnectsTo: No pre count for pre body provided.");
        } else if (preBodyPostCount == null) {
            log.error("loader.addConnectsTo: No post count for pre body provided.");
            throw new RuntimeException("loader.addConnectsTo: No post count for pre body provided.");
        } else if (preBodyRoiInfo == null) {
            log.error("loader.addConnectsTo: No roiInfo for pre body provided.");
            throw new RuntimeException("loader.addConnectsTo: No roiInfo for pre body provided.");
        } else if (datasetLabel == null) {
            log.error("loader.addConnectsTo: No dataset name provided.");
            throw new RuntimeException("loader.addConnectsTo: No dataset name provided.");
        } else if (timeStamp == null) {
            log.error("loader.addConnectsTo: No time stamp provided.");
            throw new RuntimeException("loader.addConnectsTo: No time stamp provided.");
        }

        Node preBody = getSegment(dbService, preBodyId, datasetLabel);
        Node postBody = getSegment(dbService, postBodyId, datasetLabel);

        if (preBody == null) {
            preBody = createSegment(dbService, preBodyId, datasetLabel);
        }
        if (postBody == null && !preBodyId.equals(postBodyId)) {
            postBody = createSegment(dbService, postBodyId, datasetLabel);
        } else if (postBody == null) {
            postBody = preBody;
        }

        Relationship connectsToRel = preBody.createRelationshipTo(postBody, RelationshipType.withName(CONNECTS_TO));
        connectsToRel.setProperty(WEIGHT, weight);

        preBody.setProperty(PRE, preBodyPreCount);
        preBody.setProperty(POST, preBodyPostCount);
        preBody.setProperty(TIME_STAMP, timeStamp);
        preBody.setProperty(ROI_INFO, preBodyRoiInfo);

        postBody.setProperty(TIME_STAMP, timeStamp);

        log.info("loader.addConnectsTo: exit");

    }

    public void addPostHPToConnectsTo(Node connectionSet, int postHP) {
        Node preSynapticNode = connectionSet.getSingleRelationship(RelationshipType.withName(FROM), Direction.OUTGOING).getEndNode();
        long postSynapticNodeId = connectionSet.getSingleRelationship(RelationshipType.withName(TO), Direction.OUTGOING).getEndNodeId();

        Iterable<Relationship> connectsToRelationships = preSynapticNode.getRelationships(RelationshipType.withName(CONNECTS_TO), Direction.OUTGOING);

        for (Relationship connectsToRel : connectsToRelationships) {
            long endNodeIdForRel = connectsToRel.getEndNodeId();
            if (postSynapticNodeId == endNodeIdForRel) {
                connectsToRel.setProperty(WEIGHT_HP, postHP);
            }
        }
    }

}
