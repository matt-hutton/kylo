/**
 * 
 */
package com.thinkbiganalytics.metadata.modeshape.feed;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import com.thinkbiganalytics.metadata.api.feed.Feed;
import com.thinkbiganalytics.metadata.api.feed.FeedPrecondition;
import com.thinkbiganalytics.metadata.modeshape.MetadataRepositoryException;
import com.thinkbiganalytics.metadata.modeshape.common.JcrObject;
import com.thinkbiganalytics.metadata.modeshape.sla.JcrServiceLevelAgreement;
import com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreement;
import com.thinkbiganalytics.metadata.sla.api.ServiceLevelAssessment;

/**
 *
 * @author Sean Felten
 */
public class JcrFeedPrecondition extends JcrObject implements FeedPrecondition {

    public static final String LAST_ASSESSMENT = "tba:lastAssessment";
    public static final String SLA_REF = "tba:slaRef";
    public static final String SLA = "tba:sla";

    public static final String SLA_TYPE = "tba:sla";
    public static final String LAST_ASSESSMENT_TYPE = "tba:serviceLevelAssessment";
    
    private JcrFeed feed;
    
    /**
     * 
     */
    public JcrFeedPrecondition(Node node, JcrFeed feed) {
        super(node);
        this.feed = feed;
    }
    
    public void clear() {
        try {
            if (this.node.hasProperty(SLA_REF)) {
                this.node.getProperty(SLA_REF).remove();
            }
            if (this.node.hasNode(SLA)) {
                this.node.getNode(SLA).remove();
            }
            if (this.node.hasNode(LAST_ASSESSMENT)) {
                this.node.getNode(LAST_ASSESSMENT).remove();
            }
        } catch (RepositoryException e) {
            throw new MetadataRepositoryException("Failed to cler the precondition", e);
        }
    }

    /* (non-Javadoc)
     * @see com.thinkbiganalytics.metadata.api.feed.FeedPrecondition#getFeed()
     */
    @Override
    public Feed<?> getFeed() {
        return this.feed;
    }

    /* (non-Javadoc)
     * @see com.thinkbiganalytics.metadata.api.feed.FeedPrecondition#getAgreement()
     */
    @Override
    public ServiceLevelAgreement getAgreement() {
        try {
            if (this.node.hasNode(SLA)) {
                return new JcrServiceLevelAgreement(this.node.getNode(SLA));
            } else if (this.node.hasProperty(SLA_REF)) {
                return new JcrServiceLevelAgreement(this.node.getProperty(SLA_REF).getNode());
            } else {
                return null;
            }
        } catch (RepositoryException e) {
            throw new MetadataRepositoryException("Failed to retrieve the precondition SLA", e);
        }
    }

    @Override
    public ServiceLevelAssessment getLastAssessment() {
        try {
            if (this.node.hasNode("")) {
                // TODO: create assessment
//            return new JcrServiceLevelAssessment(node);
                return null;
            } else {
                return null;
            }
        } catch (RepositoryException e) {
            throw new MetadataRepositoryException("Failed to retrieve the last precondition assessment", e);
        }
    }

    @Override
    public void setLastAssessment(ServiceLevelAssessment assmnt) {
        // TODO create assessment
    }

}
