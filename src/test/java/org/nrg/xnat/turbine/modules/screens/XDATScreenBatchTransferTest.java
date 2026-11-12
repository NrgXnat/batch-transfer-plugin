package org.nrg.xnat.turbine.modules.screens;

import org.apache.turbine.util.RunData;
import org.apache.turbine.util.parser.ParameterParser;
import org.apache.velocity.context.Context;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.turbine.utils.TurbineUtils;
import org.nrg.xft.security.UserI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The screen's entry points that need no XNAT data. */
public class XDATScreenBatchTransferTest {

    /** Opened directly, or reloaded once the session's search is gone: a message, not an error page. */
    @Test
    public void withoutAProjectOrASearch_saysWhereToOpenIt() throws Exception {
        final RunData data = mock(RunData.class);
        when(data.getParameters()).thenReturn(mock(ParameterParser.class));
        final Context context = mock(Context.class);

        try (MockedStatic<XDAT> xdat = mockStatic(XDAT.class);
             MockedStatic<TurbineUtils> turbine = mockStatic(TurbineUtils.class)) {
            xdat.when(XDAT::getUserDetails).thenReturn(mock(UserI.class));
            turbine.when(() -> TurbineUtils.getSearch(data)).thenReturn(null);

            new XDATScreen_batch_transfer().doBuildTemplate(data, context);
        }

        verify(context).put(eq("msg"), contains("Open it from a project's Actions menu, or from search results."));
        verify(context, never()).put(eq("items"), any());
    }
}
