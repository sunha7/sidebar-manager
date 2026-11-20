package com.geeta.sidebarmanager;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.config.ConfigManager;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class SidebarManager
{
    private static final String CONFIG_GROUP = "sidebarmanager";
    private static final String HIDDEN_ITEMS_KEY = "hiddenItems";
    private static final String ITEM_ORDER_KEY = "itemOrder";
    private static final String DEFAULT_STYLE =
            "tabInsets: 2,5,2,5; " +
                    "variableSize: true; " +
                    "deselectable: true; " +
                    "tabHeight: 26";

    private final SidebarManagerConfig config;
    private final ConfigManager configManager;

    private final List<SidebarItem> items =
            new ArrayList<>();

    private JTabbedPane sidebar;
    private SidebarManagerPanel panel;
    private ChangeListener sidebarChangeListener;
    private ContainerListener sidebarContainerListener;
    private ComponentListener sidebarVisibilityListener;
    private boolean changingTabs;
    private boolean navigationPending;
    private boolean correctingSelection;
    private boolean collapsedWidthApplied;
    private boolean running;
    private long lifecycleVersion;
    private long refreshVersion;
    private long resizeVersion;

    @Inject
    public SidebarManager(
            SidebarManagerConfig config,
            ConfigManager configManager)
    {
        this.config = config;
        this.configManager = configManager;
    }

    public void start()
    {
        running = true;
        long version = ++lifecycleVersion;
        SwingUtilities.invokeLater(() ->
        {
            if (!running || version != lifecycleVersion)
            {
                return;
            }

            sidebar = findSidebar();

            if (sidebar == null)
            {
                log.debug(
                        "Could not find RuneLite sidebar"
                );
                return;
            }

            captureItems();
            applySavedItemOrder();
            applyHiddenItems();
            rebuildSidebarOrder();
            applySidebarSettings();
            installSidebarListener();

            if (panel != null)
            {
                panel.refresh();
            }

            scheduleCollapsedWidthUpdate();
        });
    }

    public void stop()
    {
        running = false;
        lifecycleVersion++;
        refreshVersion++;
        if (sidebar == null)
        {
            return;
        }

        JTabbedPane stoppingSidebar = sidebar;
        SwingUtilities.invokeLater(() ->
        {
            if (sidebar != stoppingSidebar)
            {
                return;
            }
            removeSidebarListener();

            sidebar.setPreferredSize(null);
            sidebar.setMinimumSize(null);
            sidebar.setMaximumSize(null);
            collapsedWidthApplied = false;

            restoreRuneLiteSidebar();

            items.clear();
            sidebar = null;
            navigationPending = false;
        });
    }

    public void applySettings()
    {
        SwingUtilities.invokeLater(() ->
        {
            if (sidebar == null || navigationPending)
            {
                return;
            }

            applySidebarSettings();
        });
    }

    JTabbedPane findSidebar()
    {
        for (Frame frame : Frame.getFrames())
        {
            JTabbedPane found =
                    findTabbedPane(frame);

            if (found != null)
            {
                return found;
            }
        }

        return null;
    }

    private JTabbedPane findTabbedPane(
            Container container)
    {
        for (Component component :
                container.getComponents())
        {
            if (component instanceof JTabbedPane)
            {
                JTabbedPane tabbedPane =
                        (JTabbedPane) component;

                if (tabbedPane.getTabPlacement()
                        == JTabbedPane.RIGHT)
                {
                    return tabbedPane;
                }
            }

            if (component instanceof Container)
            {
                JTabbedPane result =
                        findTabbedPane(
                                (Container) component
                        );

                if (result != null)
                {
                    return result;
                }
            }
        }

        return null;
    }

    private void captureItems()
    {
        items.clear();

        for (int i = 0;
             i < sidebar.getTabCount();
             i++)
        {
            String tooltip =
                    sidebar.getToolTipTextAt(i);

            items.add(
                    new SidebarItem(
                            tooltip,
                            sidebar.getComponentAt(i),
                            sidebar.getIconAt(i),
                            tooltip,
                            i
                    )
            );
        }

    }

    private void applySidebarSettings()
    {
        if (sidebar == null)
        {
            return;
        }

        updateLayout();
        updateStyle();

        for (int i = 0;
             i < sidebar.getTabCount();
             i++)
        {
            updateTab(i);
        }

        sidebar.revalidate();
        sidebar.repaint();

        scheduleCollapsedWidthUpdate();
    }

    private void updateTab(int index)
    {
        Component component =
                sidebar.getComponentAt(index);

        SidebarItem item =
                findItem(component);

        if (item != null)
        {
            Icon originalIcon =
                    item.getOriginalIcon();

            if (originalIcon != null)
            {
                if (config.iconSize() ==
                        originalIcon.getIconWidth() &&
                        config.iconSize() ==
                                originalIcon.getIconHeight())
                {
                    sidebar.setIconAt(
                            index,
                            originalIcon
                    );
                }
                else
                {
                    BufferedImage sourceIcon =
                            iconToBufferedImage(
                                    originalIcon
                            );

                    BufferedImage resizedIcon =
                            ImageUtil.resizeImage(
                                    sourceIcon,
                                    config.iconSize(),
                                    config.iconSize()
                            );

                    sidebar.setIconAt(
                            index,
                            new ImageIcon(
                                    resizedIcon
                            )
                    );
                }
            }
        }

        if (config.showPluginNames())
        {
            String name =
                    item != null
                            ? item.getName()
                            : sidebar.getToolTipTextAt(index);

            if (name == null)
            {
                name = "";
            }

            sidebar.setTitleAt(
                    index,
                    name
            );
        }
        else
        {
            sidebar.setTitleAt(
                    index,
                    null
            );
        }
    }

    private SidebarItem findItem(
            Component component)
    {
        for (SidebarItem item : items)
        {
            if (item.getComponent() == component)
            {
                return item;
            }
        }

        return null;
    }

    private BufferedImage iconToBufferedImage(
            Icon icon)
    {
        BufferedImage image =
                new BufferedImage(
                        icon.getIconWidth(),
                        icon.getIconHeight(),
                        BufferedImage.TYPE_INT_ARGB
                );

        Graphics2D graphics =
                image.createGraphics();

        icon.paintIcon(
                null,
                graphics,
                0,
                0
        );

        graphics.dispose();

        return image;
    }

    private void updateLayout()
    {
        if (config.scrollableSidebar())
        {
            sidebar.setTabLayoutPolicy(
                    JTabbedPane.SCROLL_TAB_LAYOUT
            );
        }
        else
        {
            sidebar.setTabLayoutPolicy(
                    JTabbedPane.WRAP_TAB_LAYOUT
            );
        }
    }

    private void updateStyle()
    {
        int vertical =
                config.iconSpacing();

        int horizontal =
                config.sidebarPadding();

        String alignment;

        switch (config.nameAlignment())
        {
            case RIGHT:
                alignment = "trailing";
                break;

            case CENTER:
                alignment = "center";
                break;

            case LEFT:
            default:
                alignment = "leading";
                break;
        }

        String style =
                "tabInsets: "
                        + vertical + ","
                        + horizontal + ","
                        + vertical + ","
                        + horizontal + "; "
                        + "variableSize: true; "
                        + "deselectable: true; "
                        + "tabHeight: "
                        + (config.iconSize()
                        + (vertical * 2))
                        + "; "
                        + "tabAlignment: "
                        + alignment;

        sidebar.putClientProperty(
                FlatClientProperties.STYLE,
                style
        );
    }

    private void installSidebarListener()
    {
        if (sidebarChangeListener != null)
        {
            return;
        }

        sidebarChangeListener = event ->
        {
            correctProgrammaticSelection();
            scheduleCollapsedWidthUpdate();
        };
        sidebar.addChangeListener(sidebarChangeListener);

        sidebarContainerListener = new ContainerAdapter()
        {
            @Override
            public void componentAdded(ContainerEvent event)
            {
                if (changingTabs)
                {
                    return;
                }

                int index = sidebar.indexOfComponent(event.getChild());
                if (index == -1 || findItem(event.getChild()) != null)
                {
                    return;
                }

                // RuneLite inserts at its native navigation index, even when our list is reordered.
                for (SidebarItem item : items)
                {
                    if (item.getOriginalIndex() >= index)
                    {
                        item.setOriginalIndex(item.getOriginalIndex() + 1);
                    }
                }

                String tooltip = sidebar.getToolTipTextAt(index);
                items.add(new SidebarItem(tooltip, event.getChild(), sidebar.getIconAt(index), tooltip, index));
                scheduleSidebarRefresh();
            }

            @Override
            public void componentRemoved(ContainerEvent event)
            {
                if (changingTabs)
                {
                    return;
                }

                SidebarItem removed = findItem(event.getChild());
                if (removed == null)
                {
                    return;
                }

                items.remove(removed);
                for (SidebarItem item : items)
                {
                    if (item.getOriginalIndex() > removed.getOriginalIndex())
                    {
                        item.setOriginalIndex(item.getOriginalIndex() - 1);
                    }
                }
                scheduleSidebarRefresh();
            }
        };
        sidebar.addContainerListener(sidebarContainerListener);

        sidebarVisibilityListener = new ComponentAdapter()
        {
            @Override
            public void componentShown(ComponentEvent event)
            {
                scheduleCollapsedWidthUpdate();
            }

            @Override
            public void componentHidden(ComponentEvent event)
            {
                scheduleCollapsedWidthUpdate();
            }

            @Override
            public void componentResized(ComponentEvent event)
            {
                // RuneLite may show the sidebar before it has a real height; the
                // first resize is the earliest safe point to apply our width.
                scheduleCollapsedWidthUpdate();
            }
        };
        sidebar.addComponentListener(sidebarVisibilityListener);
    }

    /**
     * RuneLite addresses panels by tab index: ClientUI.openPanel() selects
     * sidebarEntries.headSet(navBtn).size(), i.e. the tab's priority-sorted
     * position. Reordering or hiding tabs breaks that assumption, so an action
     * such as right-click > Lookup opens whichever tab was moved into the slot
     * the HiScore panel used to occupy. Remap those programmatic selections to
     * the tab the caller actually asked for.
     */
    private void correctProgrammaticSelection()
    {
        if (sidebar == null || navigationPending || changingTabs || correctingSelection)
        {
            return;
        }

        if (!isProgrammaticOpenPanelSelection())
        {
            return;
        }

        int selectedIndex = sidebar.getSelectedIndex();
        if (selectedIndex < 0)
        {
            return;
        }

        SidebarItem intended = findItemByOriginalIndex(selectedIndex);
        if (intended == null || intended == findItem(sidebar.getComponentAt(selectedIndex)))
        {
            return;
        }

        int actualIndex = sidebar.indexOfComponent(intended.getComponent());
        if (actualIndex == -1)
        {
            // The requested panel is hidden by this plugin; bring it back so the
            // lookup (or any other openPanel call) can actually show its result.
            showItem(intended);
            actualIndex = sidebar.indexOfComponent(intended.getComponent());
        }

        // Inserting a previously hidden tab shifts the selected index, so compare
        // components rather than the index captured before showItem().
        if (actualIndex != -1 && sidebar.getSelectedComponent() != intended.getComponent())
        {
            correctingSelection = true;
            try
            {
                sidebar.setSelectedIndex(actualIndex);
            }
            finally
            {
                correctingSelection = false;
            }
        }
    }

    boolean isProgrammaticOpenPanelSelection()
    {
        for (StackTraceElement element : new Throwable().getStackTrace())
        {
            if ("net.runelite.client.ui.ClientUI".equals(element.getClassName())
                    && "openPanel".equals(element.getMethodName()))
            {
                return true;
            }
        }
        return false;
    }

    private SidebarItem findItemByOriginalIndex(int originalIndex)
    {
        for (SidebarItem item : items)
        {
            if (item.getOriginalIndex() == originalIndex)
            {
                return item;
            }
        }
        return null;
    }

    public void onPluginChanged()
    {
        assert SwingUtilities.isEventDispatchThread();
        if (!running || sidebar == null)
        {
            return;
        }

        if (!navigationPending)
        {
            navigationPending = true;
            // ClientToolbar queues navigation mutations on the EDT. PluginChanged is posted
            // synchronously before those mutations run. Restore the native tabs for that window:
            // an already-hidden tab must be present for RuneLite's remove(component) to find it,
            // and insertTab's native index must include the hidden tabs preceding a new entry.
            restoreTrackedTabs();
        }
        scheduleSidebarRefresh();
    }

    private void scheduleSidebarRefresh()
    {
        if (!running)
        {
            return;
        }

        long version = ++refreshVersion;
        JTabbedPane currentSidebar = sidebar;
        SwingUtilities.invokeLater(() ->
        {
            if (!running || sidebar != currentSidebar || version != refreshVersion)
            {
                return;
            }

            // A batch can contain several lifecycle events and queued navigation changes.
            // Only the last callback reapplies preferences, after those changes have finished.
            navigationPending = false;
            items.sort((a, b) -> Integer.compare(a.getOriginalIndex(), b.getOriginalIndex()));
            applySavedItemOrder();
            rebuildSidebarOrder();
            applySidebarSettings();
            if (panel != null)
            {
                panel.refresh();
            }
        });
    }

    private void removeSidebarListener()
    {
        if (sidebarContainerListener != null)
        {
            sidebar.removeContainerListener(sidebarContainerListener);
            sidebarContainerListener = null;
        }
        if (sidebarVisibilityListener != null)
        {
            sidebar.removeComponentListener(sidebarVisibilityListener);
            sidebarVisibilityListener = null;
        }
        if (sidebarChangeListener == null)
        {
            return;
        }

        sidebar.removeChangeListener(
                sidebarChangeListener
        );

        sidebarChangeListener = null;
    }

    /**
     * Coalesces the size updates triggered by tab changes, lifecycle refreshes and
     * visibility changes. RuneLite's ClientUI.Layout keeps its own record of the
     * content width, so every extra layout pass with a stale record can turn a
     * sidebar resize into a game-canvas resize.
     */
    private void scheduleCollapsedWidthUpdate()
    {
        if (!running || sidebar == null)
        {
            return;
        }

        JTabbedPane currentSidebar = sidebar;
        long version = ++resizeVersion;
        SwingUtilities.invokeLater(() ->
        {
            if (!running || sidebar != currentSidebar || version != resizeVersion)
            {
                return;
            }
            updateCollapsedWidth();
        });
    }

    private void updateCollapsedWidth()
    {
        if (sidebar == null || navigationPending)
        {
            return;
        }

        boolean needsNaturalWidth =
                !config.scrollableSidebar()
                        || sidebar.getSelectedIndex() != -1;

        if (needsNaturalWidth)
        {
            // While hidden the size is ignored by RuneLite, so there is no need to
            // force a layout. A panel selection still clears the override so the
            // next open uses RuneLite's natural panel width immediately.
            boolean changed = clearSidebarSizeConstraint();
            if (changed && sidebar.isShowing())
            {
                revalidateSidebarLayout();
            }
            return;
        }

        // Do not override the preferred size before RuneLite has laid the sidebar
        // out. Doing so corrupts the initial frame bounds restore and makes the
        // first panel open shrink the game canvas instead of growing the window.
        if (!sidebar.isShowing() || sidebar.getHeight() <= 0)
        {
            return;
        }

        int tabWidth = calculateTabStripWidth();
        if (tabWidth <= 0)
        {
            return;
        }

        Dimension collapsedSize =
                new Dimension(tabWidth, sidebar.getHeight());

        if (collapsedSize.equals(sidebar.getPreferredSize()))
        {
            return;
        }

        sidebar.setPreferredSize(collapsedSize);
        collapsedWidthApplied = true;
        revalidateSidebarLayout();
    }

    private int calculateTabStripWidth()
    {
        int maxWidth = 0;

        for (int i = 0;
             i < sidebar.getTabCount();
             i++)
        {
            Rectangle bounds =
                    sidebar.getBoundsAt(i);

            if (bounds != null &&
                    bounds.width > maxWidth)
            {
                maxWidth = bounds.width;
            }
        }

        if (maxWidth > 0)
        {
            return maxWidth;
        }

        int iconWidth =
                config.iconSize();

        int padding =
                config.sidebarPadding() * 2;

        int estimatedWidth =
                iconWidth + padding + 4;

        if (config.showPluginNames())
        {
            int widestText = 0;

            for (int i = 0;
                 i < sidebar.getTabCount();
                 i++)
            {
                String text =
                        sidebar.getToolTipTextAt(i);

                if (text == null)
                {
                    continue;
                }

                int textWidth =
                        sidebar
                                .getFontMetrics(
                                        sidebar.getFont()
                                )
                                .stringWidth(text);

                if (textWidth > widestText)
                {
                    widestText =
                            textWidth;
                }
            }

            estimatedWidth +=
                    widestText + 8;
        }

        return estimatedWidth;
    }

    private boolean clearSidebarSizeConstraint()
    {
        if (sidebar == null || !collapsedWidthApplied)
        {
            return false;
        }

        collapsedWidthApplied = false;
        sidebar.setPreferredSize(null);
        return true;
    }

    /**
     * Invalidates only the sidebar and its content parent. RuneLite's own
     * toggleSidebar() revalidates the same parent and owns resizing the frame;
     * walking further up forces extra layout passes with stale width bookkeeping.
     */
    private void revalidateSidebarLayout()
    {
        if (sidebar == null)
        {
            return;
        }

        sidebar.revalidate();
        sidebar.repaint();

        Container parent = sidebar.getParent();
        if (parent != null)
        {
            parent.revalidate();
            parent.repaint();
        }
    }

    private void restoreRuneLiteSidebar()
    {
        clearSidebarSizeConstraint();

        restoreTrackedTabs();
        sidebar.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
        sidebar.putClientProperty(FlatClientProperties.STYLE, DEFAULT_STYLE);

        sidebar.revalidate();
        sidebar.repaint();
    }

    private void restoreTrackedTabs()
    {
        Component selectedComponent = sidebar.getSelectedComponent();

        List<SidebarItem> originalOrder = new ArrayList<>(items);
        originalOrder.sort((a, b) -> Integer.compare(a.getOriginalIndex(), b.getOriginalIndex()));

        for (SidebarItem item : originalOrder)
        {
            int index = sidebar.indexOfComponent(item.getComponent());

            if (index != -1)
            {
                removeTabAt(index);
            }
        }

        for (SidebarItem item : originalOrder)
        {
            insertTab(null, item.getOriginalIcon(), item.getComponent(), item.getTooltip(), sidebar.getTabCount());
        }

        for (SidebarItem item : originalOrder)
        {
            int index = sidebar.indexOfComponent(item.getComponent());

            if (index == -1)
            {
                continue;
            }

            sidebar.setTitleAt(index, null);
            sidebar.setIconAt(index, item.getOriginalIcon());
            sidebar.setToolTipTextAt(index, item.getTooltip());
        }

        sidebar.setSelectedIndex(selectedComponent == null ? -1 : sidebar.indexOfComponent(selectedComponent));
    }

    private void removeTabAt(int index)
    {
        boolean wasChangingTabs = changingTabs;
        changingTabs = true;
        try
        {
            sidebar.removeTabAt(index);
        }
        finally
        {
            changingTabs = wasChangingTabs;
        }
    }

    private void insertTab(String title, Icon icon, Component component, String tooltip, int index)
    {
        boolean wasChangingTabs = changingTabs;
        changingTabs = true;
        try
        {
            sidebar.insertTab(title, icon, component, tooltip, index);
        }
        finally
        {
            changingTabs = wasChangingTabs;
        }
    }

    String getStoredValue(String key)
    {
        return configManager.getConfiguration(CONFIG_GROUP, key);
    }

    void setStoredValue(String key, String value)
    {
        if (value == null)
        {
            configManager.unsetConfiguration(CONFIG_GROUP, key);
        }
        else
        {
            configManager.setConfiguration(CONFIG_GROUP, key, value);
        }
    }

    public void hideItem(SidebarItem item)
    {
        if (sidebar == null || navigationPending || !items.contains(item) || isManagerItem(item))
        {
            return;
        }

        int index = sidebar.indexOfComponent(item.getComponent());

        if (index == -1)
        {
            return;
        }

        removeTabAt(index);
        setHidden(item, true);

        sidebar.revalidate();
        sidebar.repaint();

        scheduleCollapsedWidthUpdate();
    }

    public void showItem(SidebarItem item)
    {
        if (sidebar == null || navigationPending || !items.contains(item))
        {
            return;
        }

        if (sidebar.indexOfComponent(item.getComponent()) != -1)
        {
            return;
        }

        int insertIndex = findInsertIndex(item);

        insertTab(
                null,
                item.getOriginalIcon(),
                item.getComponent(),
                item.getTooltip(),
                insertIndex
        );

        setHidden(item, false);

        updateTab(insertIndex);

        sidebar.revalidate();
        sidebar.repaint();

        scheduleCollapsedWidthUpdate();
    }

    private int findInsertIndex(
            SidebarItem item)
    {
        int insertIndex = 0;

        for (SidebarItem otherItem : items)
        {
            if (otherItem == item)
            {
                break;
            }

            if (sidebar.indexOfComponent(
                    otherItem.getComponent()) != -1)
            {
                insertIndex++;
            }
        }

        return insertIndex;
    }
    public List<SidebarItem> getItems()
    {
        return new ArrayList<>(items);
    }

    public boolean isVisible(
            SidebarItem item)
    {
        return sidebar != null &&
                sidebar.indexOfComponent(
                        item.getComponent()
                ) != -1;
    }

    public boolean isManagerItem(
            SidebarItem item)
    {
        return "Sidebar Manager".equals(
                item.getName()
        );
    }

    public void setPanel(SidebarManagerPanel panel)
    {
        this.panel = panel;
    }

    private boolean isHidden(SidebarItem item)
    {
        String hiddenItems = getStoredValue(HIDDEN_ITEMS_KEY);

        if (hiddenItems == null || hiddenItems.isEmpty())
        {
            return false;
        }

        for (String name : hiddenItems.split("\n"))
        {
            if (name.equals(item.getName()))
            {
                return true;
            }
        }

        return false;
    }

    private void setHidden(SidebarItem item, boolean hidden)
    {
        List<String> hiddenItems = new ArrayList<>();

        String current = getStoredValue(HIDDEN_ITEMS_KEY);

        if (current != null && !current.isEmpty())
        {
            for (String name : current.split("\n"))
            {
                if (!name.isEmpty())
                {
                    hiddenItems.add(name);
                }
            }
        }

        hiddenItems.remove(item.getName());

        if (hidden)
        {
            hiddenItems.add(item.getName());
        }

        if (hiddenItems.isEmpty())
        {
            setStoredValue(HIDDEN_ITEMS_KEY, null);
        }
        else
        {
            setStoredValue(HIDDEN_ITEMS_KEY, String.join("\n", hiddenItems));
        }
    }
    private void applyHiddenItems()
    {
        for (SidebarItem item : items)
        {
            if (!isManagerItem(item) && isHidden(item))
            {
                int index = sidebar.indexOfComponent(item.getComponent());

                if (index != -1)
                {
                    removeTabAt(index);
                }
            }
        }
    }

    public void showAllItems()
    {
        if (sidebar == null || navigationPending)
        {
            return;
        }

        for (SidebarItem item : items)
        {
            if (!isVisible(item))
            {
                int insertIndex = findInsertIndex(item);

                insertTab(
                        null,
                        item.getOriginalIcon(),
                        item.getComponent(),
                        item.getTooltip(),
                        insertIndex
                );

                updateTab(insertIndex);
            }
        }

        setStoredValue(HIDDEN_ITEMS_KEY, null);

        sidebar.revalidate();
        sidebar.repaint();

        if (panel != null)
        {
            panel.refresh();
        }

        scheduleCollapsedWidthUpdate();
    }

    public void applyHiddenSettings()
    {
        SwingUtilities.invokeLater(() ->
        {
            if (sidebar == null || navigationPending)
            {
                return;
            }

            for (SidebarItem item : items)
            {
                boolean shouldBeHidden =
                        !isManagerItem(item) && isHidden(item);

                boolean currentlyVisible = isVisible(item);

                if (shouldBeHidden && currentlyVisible)
                {
                    int index = sidebar.indexOfComponent(item.getComponent());

                    if (index != -1)
                    {
                        removeTabAt(index);
                    }
                }
                else if (!shouldBeHidden && !currentlyVisible)
                {
                    int insertIndex = findInsertIndex(item);

                    insertTab(
                            null,
                            item.getOriginalIcon(),
                            item.getComponent(),
                            item.getTooltip(),
                            insertIndex
                    );

                    updateTab(insertIndex);
                }
            }

            sidebar.revalidate();
            sidebar.repaint();

            if (panel != null)
            {
                panel.refresh();
            }

            scheduleCollapsedWidthUpdate();
        });
    }

    public void moveItem(SidebarItem item, int newIndex)
    {
        if (sidebar == null || navigationPending || item == null)
        {
            return;
        }

        int currentIndex = items.indexOf(item);

        if (currentIndex == -1 || newIndex < 0 || newIndex >= items.size() || currentIndex == newIndex)
        {
            return;
        }

        items.remove(currentIndex);
        items.add(newIndex, item);
        saveItemOrder();
        rebuildSidebarOrder();

        if (panel != null)
        {
            panel.refresh();
        }
    }

    private void rebuildSidebarOrder()
    {
        if (navigationPending)
        {
            return;
        }

        int selectedIndex = sidebar.getSelectedIndex();
        Component selectedComponent = selectedIndex != -1
                ? sidebar.getComponentAt(selectedIndex)
                : null;

        for (SidebarItem item : items)
        {
            int index = sidebar.indexOfComponent(item.getComponent());

            if (index != -1)
            {
                removeTabAt(index);
            }
        }

        for (SidebarItem item : items)
        {
            if (!isManagerItem(item) && isHidden(item))
            {
                continue;
            }

            insertTab(null, item.getOriginalIcon(), item.getComponent(), item.getTooltip(), sidebar.getTabCount());
            updateTab(sidebar.getTabCount() - 1);
        }

        if (selectedComponent != null)
        {
            int newSelectedIndex = sidebar.indexOfComponent(selectedComponent);
            if (newSelectedIndex != -1)
            {
                sidebar.setSelectedIndex(newSelectedIndex);
            }
        }
        else
        {
            sidebar.setSelectedIndex(-1);
        }

        sidebar.revalidate();
        sidebar.repaint();

        scheduleCollapsedWidthUpdate();
    }
    private void saveItemOrder()
    {
        List<String> names = new ArrayList<>();

        for (SidebarItem item : items)
        {
            names.add(item.getName());
        }

        // Retain saved slots for temporarily disabled plugins while reordering active ones.
        List<String> savedNames = new ArrayList<>();
        String savedOrder = getStoredValue(ITEM_ORDER_KEY);
        int nextActive = 0;
        if (savedOrder != null && !savedOrder.isEmpty())
        {
            for (String name : savedOrder.split("\n"))
            {
                if (!names.contains(name))
                {
                    savedNames.add(name);
                }
                else if (nextActive < names.size())
                {
                    savedNames.add(names.get(nextActive++));
                }
            }
        }
        savedNames.addAll(names.subList(nextActive, names.size()));
        setStoredValue(ITEM_ORDER_KEY, String.join("\n", savedNames));
    }

    private void applySavedItemOrder()
    {
        String savedOrder = getStoredValue(ITEM_ORDER_KEY);

        if (savedOrder == null || savedOrder.isEmpty())
        {
            return;
        }

        List<SidebarItem> orderedItems = new ArrayList<>();

        for (String name : savedOrder.split("\n"))
        {
            for (SidebarItem item : items)
            {
                if (name.equals(item.getName()) && !orderedItems.contains(item))
                {
                    orderedItems.add(item);
                    break;
                }
            }
        }

        for (SidebarItem item : items)
        {
            if (!orderedItems.contains(item))
            {
                orderedItems.add(item);
            }
        }

        items.clear();
        items.addAll(orderedItems);
    }

    public void resetItemOrder()
    {
        if (sidebar == null)
        {
            return;
        }

        items.sort((a, b) ->
                Integer.compare(a.getOriginalIndex(), b.getOriginalIndex()));

        setStoredValue(ITEM_ORDER_KEY, null);

        rebuildSidebarOrder();

        if (panel != null)
        {
            panel.refresh();
        }
    }
    public void applyOrderSettings()
    {
        SwingUtilities.invokeLater(() ->
        {
            if (sidebar == null || navigationPending)
            {
                return;
            }

            items.sort((a, b) ->
                    Integer.compare(a.getOriginalIndex(), b.getOriginalIndex()));

            applySavedItemOrder();
            rebuildSidebarOrder();

            if (panel != null)
            {
                panel.refresh();
            }
        });
    }
}
