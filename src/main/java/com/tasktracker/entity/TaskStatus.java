package com.tasktracker.entity;

public enum TaskStatus {
    // Set automatically when an Admin creates a task without picking an owner.
    // Never selectable by a user/admin directly via the status dropdown - it only
    // changes via task creation (into it) or assignment (out of it).
    UNASSIGNED,
    PENDING,
    IN_PROGRESS,
    COMPLETED
}
