package com.barbarysoftware.watchservice;

import com.barbarysoftware.jna.*;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * This class contains the bulk of my implementation of the Watch Service API. It hooks into Carbon's
 * File System Events API.
 *
 * @author Steve McLeod
 */
class MacOSXListeningWatchService extends AbstractWatchService {

    // need to keep reference to callbacks to prevent garbage collection
    @SuppressWarnings({"MismatchedQueryAndUpdateOfCollection"})
    private final List<CarbonAPI.FSEventStreamCallback> callbackList = new ArrayList<CarbonAPI.FSEventStreamCallback>();
    private final List<CFRunLoopThread> threadList = new ArrayList<CFRunLoopThread>();

    @Override
    WatchKey register(WatchableFile watchableFile, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifers) throws IOException {
        final File file = watchableFile.getFile();
        final String s = file.getAbsolutePath();
        final Pointer[] values = {CFStringRef.toCFString(s).getPointer()};
        final CFArrayRef pathsToWatch = CarbonAPI.INSTANCE.CFArrayCreate(null, values, CFIndex.valueOf(1), null);
        final MacOSXWatchKey watchKey = new MacOSXWatchKey(this, events);

        final double latency = 1.0; /* Latency in seconds */

        final long kFSEventStreamEventIdSinceNow = -1; //  this is 0xFFFFFFFFFFFFFFFF
        final int kFSEventStreamCreateFlagFileEvents = 0x00000010;
        final CarbonAPI.FSEventStreamCallback callback = new MacOSXListeningCallback(watchKey);
        callbackList.add(callback);
        final FSEventStreamRef stream = CarbonAPI.INSTANCE.FSEventStreamCreate(
                Pointer.NULL,
                callback,
                Pointer.NULL,
                pathsToWatch,
                kFSEventStreamEventIdSinceNow,
                latency,
                kFSEventStreamCreateFlagFileEvents);

        final CFRunLoopThread thread = new CFRunLoopThread(stream, file);
        thread.setDaemon(true);
        thread.start();
        threadList.add(thread);
        return watchKey;
    }

    public static class CFRunLoopThread extends Thread {

        private final FSEventStreamRef streamRef;
        private CFRunLoopRef runLoop;

        public CFRunLoopThread(FSEventStreamRef streamRef, File file) {
            super("WatchService for " + file);
            this.streamRef = streamRef;
        }

        @Override
        public void run() {
            runLoop = CarbonAPI.INSTANCE.CFRunLoopGetCurrent();
            final CFStringRef runLoopMode = CFStringRef.toCFString("kCFRunLoopDefaultMode");
            CarbonAPI.INSTANCE.FSEventStreamScheduleWithRunLoop(streamRef, runLoop, runLoopMode);
            CarbonAPI.INSTANCE.FSEventStreamStart(streamRef);
            CarbonAPI.INSTANCE.CFRunLoopRun();
        }

        public CFRunLoopRef getRunLoop() {
            return runLoop;
        }

        public FSEventStreamRef getStreamRef() {
            return streamRef;
        }
    }

    @Override
    void implClose() throws IOException {
        for (CFRunLoopThread thread : threadList) {
            CarbonAPI.INSTANCE.CFRunLoopStop(thread.getRunLoop());
            CarbonAPI.INSTANCE.FSEventStreamStop(thread.getStreamRef());
        }
        threadList.clear();
        callbackList.clear();
    }


    private static class MacOSXListeningCallback implements CarbonAPI.FSEventStreamCallback {
        private final MacOSXWatchKey watchKey;

        final int kFSEventStreamEventFlagItemCreated = 0x00000100;
        final int kFSEventStreamEventFlagItemRemoved = 0x00000200;
        final int kFSEventStreamEventFlagItemRenamed = 0x00000800;
        final int kFSEventStreamEventFlagItemModified = 0x00001000;

        private MacOSXListeningCallback(MacOSXWatchKey watchKey) {
            this.watchKey = watchKey;
        }

        public void invoke(FSEventStreamRef streamRef, Pointer clientCallBackInfo, NativeLong numEvents, Pointer eventPaths, Pointer /* array of unsigned int */ eventFlags, /* array of unsigned long */ Pointer eventIds) {
            int length = numEvents.intValue();

            for (int i = 0; i < length; i++) {
                String eventPath = eventPaths.getStringArray(0, length)[i];
                int eventFlag = eventFlags.getIntArray(0, length)[i];

                File file = new File(eventPath);

                if ((kFSEventStreamEventFlagItemCreated & eventFlag) != 0) {
                    if (watchKey.isReportCreateEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_CREATE, file);
                    }
                }

                if ((kFSEventStreamEventFlagItemRemoved & eventFlag) != 0) {
                    if (watchKey.isReportDeleteEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_DELETE, file);
                    }
                }

                if ((kFSEventStreamEventFlagItemModified & eventFlag) != 0) {
                    if (watchKey.isReportModifyEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_MODIFY, file);
                    }
                }

                if ((kFSEventStreamEventFlagItemRenamed & eventFlag) != 0) {
                    Path filePath = Paths.get(file.getPath());

                    String realFilePathName = null;

                    try {
                        Path realFilePath = filePath.toRealPath();

                        Path realFileNameFilePath = realFilePath.getFileName();

                        realFilePathName = realFileNameFilePath.toString();
                    }
                    catch (Exception e) {
                        return;
                    }

                    if (file.exists() && eventPath.endsWith(realFilePathName)) {
                        if (watchKey.isReportRenameToEvents()) {
                            watchKey.signalEvent(ExtendedWatchEventKind.ENTRY_RENAME_TO, file);
                        }
                    }
                    else {
                        if (watchKey.isReportRenameFromEvents()) {
                            watchKey.signalEvent(ExtendedWatchEventKind.ENTRY_RENAME_FROM, file);
                        }
                    }
                }
            }
        }
    }
}
