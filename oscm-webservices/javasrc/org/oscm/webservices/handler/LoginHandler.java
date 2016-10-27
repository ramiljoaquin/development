/*******************************************************************************
 *  Copyright FUJITSU LIMITED 2016 
 *******************************************************************************/

package org.oscm.webservices.handler;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.security.auth.Subject;
import javax.sql.DataSource;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.TransformerException;
import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.handler.soap.SOAPHandler;
import javax.xml.ws.handler.soap.SOAPMessageContext;

import org.apache.commons.lang3.StringUtils;
import org.glassfish.security.common.PrincipalImpl;
import org.w3c.dom.Element;

import org.oscm.logging.Log4jLogger;
import org.oscm.logging.LoggerFactory;
import org.oscm.converter.XMLConverter;
import org.oscm.saml2.api.SAMLResponseExtractor;
import org.oscm.types.enumtypes.LogMessageIdentifier;
import org.oscm.internal.types.exception.NotExistentTenantException;
import org.oscm.internal.types.exception.UserIdNotFoundException;
import org.oscm.types.exceptions.SecurityCheckException;
import com.sun.appserv.security.ProgrammaticLogin;
import com.sun.xml.ws.security.opt.impl.incoming.SAMLAssertion;
import com.sun.xml.wss.XWSSecurityException;
import com.sun.xml.wss.impl.MessageConstants;
import com.sun.xml.wss.saml.util.SAMLUtil;

public class LoginHandler implements SOAPHandler<SOAPMessageContext> {
    
    private static final String TENANT_ID_HEADER_PARAM = "tenantId";
    private static final String ORGANIZATION_ID_HEADER_PARAM = "organizationId";
    
    private AbstractKeyQuery keyQuery;
    
    private SAMLResponseExtractor samlExtractor = new SAMLResponseExtractor();
    
    public AbstractKeyQuery getKeyQuery() {
        return keyQuery;
    }

    public void setKeyQuery(AbstractKeyQuery keyQuery) {
        this.keyQuery = keyQuery;
    }
    
    public DataSource getDbSource() throws NamingException{
        Context context = new InitialContext();
        DataSource ds = (DataSource) context.lookup("BSSDS");
        
        return ds;
    }

    @Override
    public boolean handleMessage(SOAPMessageContext context) {

        if (isInboundMessage(context).booleanValue()) {
      
            String userKey = null;
            try {
                userKey = getUserKeyFromContext(context);
            } catch (UserIdNotFoundException | NamingException | SQLException exception) {
                logException(
                        exception,
                        LogMessageIdentifier.ERROR_GET_USER_FROM_SAML_RESPONSE_FAILED);
                return false;
            }

            try {
                login(userKey);
                addPrincipal(context, userKey);
            } catch (Exception exception) {
                logException(exception,
                        LogMessageIdentifier.ERROR_USER_LOGIN_FAILED);
                return false;
            }
        }
        return true;
    }

    private void logException(Exception exception,
            LogMessageIdentifier identifier) {
        Log4jLogger logger = LoggerFactory.getLogger(LoginHandler.class);
        logger.logError(Log4jLogger.SYSTEM_LOG, exception, identifier,
                exception.getClass().getName());
    }

    private void logDebugSamlAssertion(SAMLAssertion samlAssertion) {
        Log4jLogger logger = LoggerFactory.getLogger(LoginHandler.class);
        String samlAssertionString = null;
        try {
            Element samlAssertionElement = SAMLUtil
                    .createSAMLAssertion(samlAssertion.getSamlReader());
            samlAssertionString = XMLConverter.convertToString(
                    samlAssertionElement, false);
            logger.logDebug(samlAssertionString);
        } catch (XWSSecurityException | XMLStreamException
                | TransformerException exception) {
            logger.logDebug("SAML Assertion conversion failed: "
                    + exception.getMessage());
        }
    }

    protected void addPrincipal(SOAPMessageContext context, String userKey) {
        Subject sub = (Subject) context.get(MessageConstants.AUTH_SUBJECT);
        sub.getPrincipals().add(new PrincipalImpl(userKey));
    }

    protected void login(String userKey) throws Exception {
        boolean loginOutcome = false;
        ProgrammaticLogin prlogin = new ProgrammaticLogin();
        loginOutcome = prlogin.login(userKey,
                ("WS" + System.currentTimeMillis()).toCharArray(), "bss-realm",
                false).booleanValue();
        if (!loginOutcome) {
            throw new SecurityCheckException(String.format(
                    "Login of user %s failed", userKey));
        }
    }

    protected Boolean isInboundMessage(SOAPMessageContext context) {
        Boolean outBoundProperty = (Boolean) context
                .get(MessageContext.MESSAGE_OUTBOUND_PROPERTY);
        return Boolean.valueOf(!outBoundProperty.booleanValue());
    }

    protected String getUserKeyFromContext(SOAPMessageContext context)
            throws UserIdNotFoundException, NamingException, SQLException {
        
        String userId = getUserIdFromContext(context);
        String tenantId = getTenantIdFromContext(context);
        String orgId = getOrganizationIdFromContext(context);
        
        return getUserKey(userId, orgId, tenantId);
    }
    
    private String getUserKey(String userId, String orgId, String tenantId)
            throws NamingException, SQLException {

        DataSource dbSource = getDbSource();

        if (StringUtils.isNotEmpty(tenantId)) {
            keyQuery = new UserKeyForTenantQuery(dbSource, userId, tenantId);
            
        } else if(StringUtils.isNotEmpty(orgId)){
            keyQuery = new UserKeyForOrganizationQuery(dbSource, userId, orgId);
        }
        else {
            keyQuery = new UserKeyQuery(dbSource, userId);
        }

        keyQuery.execute();
        long userKey = keyQuery.getKey();
        
        if (userKey == 0) {
            throw new SQLException(
                    "User not found [user id: " + userId + ", tenant id: "
                            + tenantId + ", orgaznization id: " + orgId + " ]");
        }
        
        return String.valueOf(userKey);
    }

    protected String getUserIdFromContext(SOAPMessageContext context)
            throws UserIdNotFoundException {
        
        String userId;
        
        SAMLAssertion samlAssertion = getSamlAssertion(context);
        SAMLResponseExtractor extractor = new SAMLResponseExtractor();

        userId = extractor.getUserId(samlAssertion);
        return userId;
    }
    
    protected String getTenantIdFromContext(SOAPMessageContext context){
        
        String tenantId = null;
        
        @SuppressWarnings("unchecked")
        Map<String, List<String>> headers = (Map<String, List<String>>) context
                .get(MessageContext.HTTP_REQUEST_HEADERS);
        
        List<String> tenantIdParams = headers.get(TENANT_ID_HEADER_PARAM);
        
        if(tenantIdParams!=null){
            tenantId = tenantIdParams.get(0);
        }
        
        return tenantId;
    }
    
    protected String getTenantIdFromSamlResponse(SOAPMessageContext context) throws NotExistentTenantException {
        
        String tenantId;
        
        SAMLAssertion samlAssertion = (SAMLAssertion) context
                .get(MessageConstants.INCOMING_SAML_ASSERTION);
        
        tenantId = samlExtractor.getTenantId(samlAssertion);
        return tenantId;
    }
    
    protected String getOrganizationIdFromContext(SOAPMessageContext context){
        
        String orgId = null;
        
        @SuppressWarnings("unchecked")
        Map<String, List<String>> headers = (Map<String, List<String>>) context
                .get(MessageContext.HTTP_REQUEST_HEADERS);
        
        List<String> orgIdParams = headers.get(ORGANIZATION_ID_HEADER_PARAM);
        
        if(orgIdParams!=null){
            orgId = orgIdParams.get(0);
        }
        
        return orgId;
    }
    
    private SAMLAssertion getSamlAssertion(SOAPMessageContext context){
        
        SAMLAssertion samlAssertion = (SAMLAssertion) context
                .get(MessageConstants.INCOMING_SAML_ASSERTION);
        
        logDebugSamlAssertion(samlAssertion);
        
        return samlAssertion;
    }
    
    @Override
    public boolean handleFault(SOAPMessageContext context) {
        return false;
    }

    @Override
    public void close(MessageContext context) {
    }

    @Override
    public Set<QName> getHeaders() {
        return null;
    }
}
