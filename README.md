# WARNING: Work in progress! Very instable, not ready for anything larger than a calculator.

salvage is my solution for backing up small scale Docker applications.
Or more specifically: **my** Docker infrastructure.
As with most things Docker related, the puns are important.
So I anchored on `salvage`.
Because we are trying to prevent losing our cargo ships.
I could go on for miles, but let's get back on course.

# Overview

salvage will identify all volumes on the current Docker instance, that need to be backed up.
It will then identify all containers attached to each volume and perform their configured *backup action* to prepare the volume for backup, ensuring data consistency.
If multiple containers are sharing a common set of volumes and you would like to back them up all at the same time, `Tides` can be configured.
A Tide tells salvage which volumes should be backup up at the same time.
All affected containers of a Tide will have their *backup action* performed at the same time, to reduce potential down time.

While salvage takes care of orchestrating all backups, it does not actually backup any data.
It delegates this job to *Cranes*.
A Crane is a Docker image, which implements the salvage Crane interface.
After salvage has prepared all attached containers for backup, it instances a Crane to perform the actual backup.
salvage will wait for the Crane to complete the backup, before reversing the performed *backup actions* on all containers, returning everything back to full operations.

This design allows to backup volumes with most file-based backup solutions, while ensuring that these solution don't need to interface with docker directly.

# Configuration

salvage is supposed to retrieve individual backup definitions by checking for certain labels on each volume but requires a global configuration to configure the backup infrastructure itself.

## Daemon configuration

The daemon configuration is devided into environment variables and labels directly attached to the salvage container.
This might seem odd at first, but since the configuration of other containers and volumes is done via labels, configuring the same things on salvage via environment variables would feel very strange.

The following environment variables are used to configure the daemon:

* `MACHINE`: Name that will be passed to cranes to identify the current machine. This can be used to differentiate between different machines on the same storage.

### Tide configuration

A Tide specifies a schedule for a set of volumes to be backed up at the same time.
Volumes can hook into a Tide and will then be backed up when the Tide is scheduled.
Each tide can specify a strategy for how to group individual volumes and their using containers to change how much downtime is expected.

The following labels are used to configure a tide and need to present on the salvage container:

* `salvage.tides.<name>.cron`: Cron expression specifying when the tide should be executed.
* `salvage.tides.<name>.grouping`: The grouping strategy to use for this tide. Possible values are:
    * `individual`: Each volume is backed up individually and ech dependent container is shut down and restarted before each volume is backed up.
    * `smart`: Strongly connected components are grouped together. Effectively all volumes and containers that can somehow be reached from each other are grouped together.
    * `project`: Same as `smart`, but containers inside the same compose project are always grouped together. The reason behind this is that it doesn't make sense to shut down individual parts of an application, as they are not operational with other services missing.
* `salvage.tides.<name>.crane`: Default crane to use for this tide. Can be overridden by individual volumes.
* `salvage.tides.<name>.maxConcurrent`: The maximum number of concurrent backups for this tide. Might be lower if there are not enough volumes per group, as groups are run in sequence. Also limited by the number of allowed crane instances.

### Crane configuration

A crane is an image that implements the salvage Crane interface.

The following labels are used to configure a crane and need to present on the salvage container:

* `salvage.cranes.<name>.image`: The image of this crane.
* `salvage.cranes.<name>.env.<key>`: Environment variables to pass to the crane. For example `salvage.cranes.<name>.env.S3_BUCKET=my-bucket`.
* `salvage.cranes.<name>.mount.<volume>`: Mounts a volume to the crane. The volume will be mounted at the specified path. For exmaples `salvage.cranes.<name>.mount.my-volume=/cache`.

## Volume configuration

By default, Salvage will ignore all volumes it hasn't been explicitly instructed to backup.
In order to configure a volume for backup, you need to label it as such.
While executing a Tide, salvage will check for the following labels on each volume:

* `salvage.tide`: The name of the Tide this volume is attached to.
* `salvage.crane`: Override the default crane to use for this volume.
* `salvage.dryRun`: If set to `true`, the volume will not be backed up, but all preparations will be done and a status message is logged instead.

### Container configuration

The following labels can be used on containers and will define how Salvage will tell the container to stop modifying the backup volume.
All modifications to a container's state will be reverted after the backup is done.
Certain actions can only be performed on a container if the container is in a certain state.

* `salvage.action`: Defines if the container state should be altered before backing up it's volumes. Possible values are:
    * `ignore`: Container state will not be altered. (Default if either pre- or post-action is set).
    * `stop`: (Default if no pre- or post-action is set) The container will be stopped before the backup is performed. (Ignored if container is already stopped.)
    * `pause`: The container will be paused before the backup is performed. (Ignored if container is already paused or stopped.)
* `salvage.command.pre` and `salvage.command.post`: Commands that will be executed before and after the backup within the container, similar to `docker exec`. Will not be executed if the container is stopped or paused.

# salvage Crane Interface

Cranes are implemented via docker images.
salvage will pull the crane image, create a container from it and preparing the backup environment.
The following environment variables are passed to the crane:

* `SALVAGE_MACHINE_NAME`: The name of the machine that is backing up, as specified in the daemon configuration.
* `SALVAGE_CRANE_NAME`: The name of the crane that is performing the backup.
* `SALVAGE_VOLUME_NAME`: The name of the volume.
* as well as any additional environment variables specified in the crane configuration.

salvage will mount the volume at `/salvage/volume` (read-only) and include some meta data at `/salvage/meta`.
The crane is expected to backup and restore both of these directories.
The content within the `/salvage/meta` directory is not part of the crane interface, and may change at any time.
Do not rely on it.
Any additional volumes will be mounted writable, as specified in the crane configuration.

# Restrictions

Due to the nature of Docker, Salvage can not protect itself from outside events, such as stop requests, system reboots or other processes messing with a container state while a backup is running.
Salvage attempts to minimize potential issues, but ultimately requires the host to simply cooperate.

# FAQ

## Why not backup the volume directory from outside of containers?

If you think that's a good idea go ahead and do it.

## Can I run multiple salvage instances on the same docker daemon?

No! salvage assumes to be the only instance running on a docker daemon.
If you run multiple instances, they will start to interfere with each other.
