/*  Copyright (C) 2009 Mobile Sorcery AB

    This program is free software; you can redistribute it and/or modify it
    under the terms of the Eclipse Public License v1.0.

    This program is distributed in the hope that it will be useful, but WITHOUT
    ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
    FITNESS FOR A PARTICULAR PURPOSE. See the Eclipse Public License v1.0 for
    more details.

    You should have received a copy of the Eclipse Public License v1.0 along
    with this program. It is also available at http://www.eclipse.org/legal/epl-v10.html
*/

// pipelib.cpp : Defines the exported functions for the DLL application.
//

//#include "stdafx.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "pipelib.h"

#include <io.h>
#include <stdio.h>
#include <fcntl.h>
#include <process.h>
#include <direct.h>

PIPELIB_API int pipe_create(int* fds)
{
	return _pipe(fds, 512, O_BINARY/* | O_NOINHERIT*/);
}

PIPELIB_API int pipe_read(int fd, void* dstbuf, int maxchars) {
	return _read(fd, dstbuf, maxchars);
}

PIPELIB_API int pipe_write(int fd, void* srcbuf, int length) {
	return _write(fd, srcbuf, length);
}

PIPELIB_API int pipe_dup(int fd) {
	return _dup(fd);
}

PIPELIB_API int pipe_dup2(int fd1, int fd2) {
	return _dup2(fd1, fd2);
}

PIPELIB_API int pipe_close(int fd) {
	return _close(fd);
}

PIPELIB_API int proc_spawn(char* cmd, char* args, char* dir) {
	if(_chdir(dir) != 0)
		return -1;
	return (int)_spawnl(_P_NOWAIT, cmd, args, NULL);
}

PIPELIB_API int proc_wait_for(int handle) {
	int err_code;
	_cwait(&err_code, handle, NULL);
	return err_code;
}

PIPELIB_API int proc_kill(int pid, int exit_code) {
	HANDLE handle = OpenProcess(PROCESS_TERMINATE, FALSE, pid);
	if (handle != NULL) {
		return TerminateProcess(handle, exit_code);
	} else {
		return -1;
	}
}

