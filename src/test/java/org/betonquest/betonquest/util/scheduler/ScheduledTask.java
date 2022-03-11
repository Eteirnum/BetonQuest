package org.betonquest.betonquest.util.scheduler;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * A scheduled task fot the {@link BukkitSchedulerMock}.
 */
public class ScheduledTask implements BukkitTask {
    /**
     * The task id of this task.
     */
    private final int taskId;
    /**
     * The plugin created this task.
     */
    private final Plugin plugin;
    /**
     * Is this task sync.
     */
    private final boolean sync;
    /**
     * The runnable to execute.
     */
    private final Runnable runnable;
    /**
     * List of runnables to execute if this task is canceled.
     */
    private final List<Runnable> cancelListeners;
    /**
     * It this task canceled.
     */
    private boolean cancelled;
    /**
     * The tick when to execute this task the next time.
     */
    private long scheduledTick;
    /**
     * Is this task currently running.
     */
    private boolean running;

    /**
     * Create a new scheduled task.
     *
     * @param taskId        the task id
     * @param plugin        the plugin from the task
     * @param sync          is the task sync
     * @param scheduledTick the tick when to execute the task
     * @param runnable      the runnable to execute
     */
    public ScheduledTask(final int taskId, final Plugin plugin, final boolean sync, final long scheduledTick, final Runnable runnable) {
        this.cancelListeners = new LinkedList<>();
        this.taskId = taskId;
        this.plugin = plugin;
        this.sync = sync;
        this.scheduledTick = scheduledTick;
        this.runnable = runnable;
        this.running = false;
    }


    public boolean isRunning() {
        return running;
    }

    public void setRunning(final boolean running) {
        this.running = running;
    }


    /**
     * Get the tick at which the task is scheduled to run at.
     *
     * @return The tick the task is scheduled to run at.
     */
    public long getScheduledTick() {
        return scheduledTick;
    }

    /**
     * Sets the tick at which the task is scheduled to run at.
     *
     * @param scheduledTick The tick at which the task is scheduled to run at.
     */
    protected void setScheduledTick(final long scheduledTick) {
        this.scheduledTick = scheduledTick;
    }

    /**
     * Get the task itself that will be run.
     *
     * @return The task that will be run.
     */
    public Runnable getRunnable() {
        return runnable;
    }

    /**
     * Runs the task if it has not been cancelled.
     */
    public void run() {
        if (isCancelled()) {
            throw new CancellationException("Task is cancelled");
        }
        runnable.run();
    }

    @Override
    public int getTaskId() {
        return taskId;
    }

    @NotNull
    @Override
    public Plugin getOwner() {
        return plugin;
    }

    @Override
    public boolean isSync() {
        return sync;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
        cancelListeners.forEach(Runnable::run);
    }

    /**
     * Adds a callback which is executed when the task is cancelled.
     *
     * @param callback The callback which gets executed when the task is cancelled.
     */
    public void addOnCancelled(final Runnable callback) {
        cancelListeners.add(callback);
    }

}
