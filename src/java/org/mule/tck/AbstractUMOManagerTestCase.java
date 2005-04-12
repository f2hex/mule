/*
 * $Header$
 * $Revision$
 * $Date$
 * ------------------------------------------------------------------------------------------------------
 *
 * Copyright (c) Cubis Limited. All rights reserved.
 * http://www.cubis.co.uk
 *
 * The software in this package is published under the terms of the BSD
 * style license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */
package org.mule.tck;

import org.mule.MuleManager;
import org.mule.impl.MuleModel;
import org.mule.interceptors.LoggingInterceptor;
import org.mule.umo.endpoint.UMOEndpoint;
import org.mule.umo.manager.UMOManager;
import org.mule.umo.provider.UMOConnector;
import org.mule.umo.transformer.UMOTransformer;

import java.util.ArrayList;
import java.util.List;

/**
 * @author <a href="mailto:ross.mason@cubis.co.uk">Ross Mason</a>
 * @version $Revision$
 */

public abstract class AbstractUMOManagerTestCase extends AbstractMuleTestCase
{
    private static boolean initialised = false;

    protected void setUp() throws Exception
    {
        if(!initialised) {
            getUMOManager();
            initialised=true;
        }
    }

    public abstract UMOManager getUMOManager() throws Exception;

    public void testConnectorLookup() throws Exception
    {
        UMOConnector connector = MuleManager.getInstance().lookupConnector("testConnector");
        assertNotNull(connector);
        assertEquals(1, MuleManager.getInstance().getConnectors().size());
        UMOConnector connector2 = getTestConnector();
        MuleManager.getInstance().registerConnector(connector2);
        assertEquals(2, MuleManager.getInstance().getConnectors().size());

        assertNull(MuleManager.getInstance().lookupConnector("doesnotexist"));
    }

    public void testEndpointLookup() throws Exception
    {
        UMOEndpoint endpoint = MuleManager.getInstance().lookupEndpoint("testEndpoint");
        assertNotNull(endpoint);
        assertEquals(1, MuleManager.getInstance().getEndpoints().size());
        UMOEndpoint endpoint2 = getTestEndpoint("testProvider2", UMOEndpoint.ENDPOINT_TYPE_SENDER);
        MuleManager.getInstance().registerEndpoint(endpoint2);
        assertEquals(2, MuleManager.getInstance().getEndpoints().size());

        UMOEndpoint endpoint3 = MuleManager.getInstance().lookupEndpoint("doesnotexist");
        assertNull(endpoint3);
    }

    public void testTransformerLookup() throws Exception
    {
        UMOTransformer transformer = MuleManager.getInstance().lookupTransformer("testTransformer");
        assertNotNull(transformer);
        assertEquals(1, MuleManager.getInstance().getTransformers().size());
        UMOTransformer transformer2 = getTestTransformer();
        MuleManager.getInstance().registerTransformer(transformer2);
        assertEquals(2, MuleManager.getInstance().getTransformers().size());

        UMOTransformer transformer3 = MuleManager.getInstance().lookupTransformer("doesnotexist");
        assertNull(transformer3);
    }

    public void testEndpointIdentifierLookup() throws Exception
    {
        String endpoint = MuleManager.getInstance().lookupEndpointIdentifier("testEndpointURI", null);
        assertNotNull(endpoint);
        assertEquals("test://endpoint.test", endpoint);
        assertEquals(1, MuleManager.getInstance().getEndpointIdentifiers().size());
        MuleManager.getInstance().registerEndpointIdentifier("testEndpoint2", "endpointUri.test.2");
        assertEquals(2, MuleManager.getInstance().getEndpointIdentifiers().size());

        String endpoint2 = MuleManager.getInstance().lookupEndpointIdentifier("doesnotexist", null);
        assertNull(endpoint2);
    }

    public void testManagerProperties()
    {
        String value = (String)MuleManager.getInstance().getProperty("envProperty1");
        assertEquals("value1", value);
        assertEquals(1, MuleManager.getInstance().getProperties().size());
    }

    public void testInterceptorStacks()
    {
        List interceptors = MuleManager.getInstance().lookupInterceptorStack("testInterceptorStack");
        assertEquals(2, interceptors.size());

        List interceptors2 = new ArrayList();
        interceptors2.add(new LoggingInterceptor());

        MuleManager.getInstance().registerInterceptorStack("testInterceptors2", interceptors2);

        assertEquals(1, MuleManager.getInstance().lookupInterceptorStack("testInterceptors2").size());

        List interceptors3 = MuleManager.getInstance().lookupInterceptorStack("doesnotexist");
        assertNull(interceptors3);
    }

    public void testTrasactionSetting() throws Exception
    {
        assertNotNull(MuleManager.getInstance().getTransactionManager());
        try
        {
            MuleManager.getInstance().setTransactionManager(null);
            fail("cannot set tx manager once it has been set");
        } catch (Exception e)
        {
            //expected
        }
    }

//    public void testManagerLifecycle() throws Exception
//    {
//        MuleManager.getInstance().start();
//        UMOConnector connector = MuleManager.getInstance().lookupConnector("testConnector");
//        assertTrue(connector.isStarted());
//
//        MuleManager.getInstance().stop();
//        assertTrue(!connector.isStarted());
//
//        MuleManager.getInstance().dispose();
//        assertTrue(connector.isDisposed());
//    }

    public void testModelSetting() {
        assertNotNull(MuleManager.getInstance().getModel());
        MuleManager.getInstance().setModel(new MuleModel());
        assertEquals("mule", MuleManager.getInstance().getModel().getName());
    }
}
