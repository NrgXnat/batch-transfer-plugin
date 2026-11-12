package org.nrg.xnat.turbine.modules.screens;

import org.apache.turbine.util.RunData;
import org.apache.turbine.util.parser.ParameterParser;
import org.apache.velocity.context.Context;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.search.DisplaySearch;
import org.nrg.xdat.turbine.utils.TurbineUtils;
import org.nrg.xft.security.UserI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The screen's entry points that need no XNAT data: no source, and the preset destination. */
public class XDATScreenBatchTransferTest {

    private RunData                  data;
    private ParameterParser          params;
    private Context                  context;
    private UserI                    user;
    private XDATScreen_batch_transfer screen;

    @Before
    public void setUp() {
        data = mock(RunData.class);
        params = mock(ParameterParser.class);
        when(data.getParameters()).thenReturn(params);
        context = mock(Context.class);
        user = mock(UserI.class);
        screen = spy(new XDATScreen_batch_transfer());
        doReturn(false).when(screen).isSiteAdmin(any(UserI.class));
    }

    /** From the Tools menu, a link, or a reload once the session's search is gone: History only, not an error page. */
    @Test
    public void withoutAProjectOrASearch_showsHistoryOnly() throws Exception {
        doReturn(false).when(screen).isSiteAdmin(user);

        build();

        verify(context).put("historyOnly", true);
        verify(context).put("siteAdmin", false);
        verify(context, never()).put(eq("msg"), any());
        verify(context, never()).put(eq("items"), any());
    }

    @Test
    public void withoutAProjectOrASearch_tellsTheTemplateASiteAdminIsViewing() throws Exception {
        doReturn(true).when(screen).isSiteAdmin(user);

        build();

        verify(context).put("siteAdmin", true);
    }

    /** Tools > Batch Transfer History: a search another XNAT page left in the session is never looked up or run. */
    @Test
    public void historyOnlyAskedFor_ignoresASearchInTheSession() throws Exception {
        when(params.getBoolean(XDATScreen_batch_transfer.HISTORY_ONLY_PARAM)).thenReturn(true);
        final DisplaySearch leftOver = mock(DisplaySearch.class);

        try (MockedStatic<XDAT> xdat = mockStatic(XDAT.class);
             MockedStatic<TurbineUtils> turbine = mockStatic(TurbineUtils.class)) {
            xdat.when(XDAT::getUserDetails).thenReturn(user);
            turbine.when(() -> TurbineUtils.getSearch(data)).thenReturn(leftOver);
            screen.doBuildTemplate(data, context);
            turbine.verify(() -> TurbineUtils.getSearch(data), never());
        }

        verify(context).put("historyOnly", true);
        verify(context, never()).put(eq("items"), any());
        verifyNoInteractions(leftOver);
    }

    /** The destination parameter never reaches the page unless it names a project. */
    @Test
    public void aDestinationThatNamesNoProject_isIgnored() throws Exception {
        final String injected = "X';alert(1);//<b>";
        when(params.getString("destination")).thenReturn(injected);
        doReturn(null).when(screen).projectId(injected, user);

        build();

        verify(context, never()).put(eq("presetDestination"), any());
    }

    @Test
    public void aDestinationAlias_isPassedOnAsTheProjectsId() throws Exception {
        when(params.getString("destination")).thenReturn("dest-alias");
        doReturn("DEST").when(screen).projectId("dest-alias", user);

        build();

        verify(context).put("presetDestination", "DEST");
    }

    private void build() throws Exception {
        try (MockedStatic<XDAT> xdat = mockStatic(XDAT.class);
             MockedStatic<TurbineUtils> turbine = mockStatic(TurbineUtils.class)) {
            xdat.when(XDAT::getUserDetails).thenReturn(user);
            turbine.when(() -> TurbineUtils.getSearch(data)).thenReturn(null);
            screen.doBuildTemplate(data, context);
        }
    }
}
