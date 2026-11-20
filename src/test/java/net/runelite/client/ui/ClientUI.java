package net.runelite.client.ui;

import javax.swing.JTabbedPane;

/**
 * Test-only stand-in for RuneLite's ClientUI. SidebarManager detects programmatic
 * panel openings by looking for a ClientUI.openPanel frame on the stack; this
 * lets the tests exercise that detection without launching the real client.
 */
public final class ClientUI
{
    private ClientUI()
    {
    }

    public static void openPanel(JTabbedPane sidebar, int index)
    {
        sidebar.setSelectedIndex(index);
    }
}
