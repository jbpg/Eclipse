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
package com.mobilesorcery.sdk.internal;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;

import com.mobilesorcery.sdk.core.IFilter;

public class PathExclusionFilter implements IFilter<IResource> {
	
	private String[] filespecs;

	PathExclusionFilter(String[] filespecs) {
		this.filespecs = filespecs;
	}

	public static PathExclusionFilter parse(String[] filespecs) {
		return new PathExclusionFilter(filespecs);
	}

	public boolean accept(IResource resource) {
		return !inverseAccept(resource);
	}
	
	public boolean inverseAccept(IResource resource) {
		for (int i = 0; i < filespecs.length; i++) {
			if (accept(resource, filespecs[i])) {
				return true;
			}
		}
		
		return false;
	}
	
	protected boolean accept(IResource resource, String filespec) {
		// Just simple exclusion list for now.
		Path filePath = new Path(filespec);
		IResource fileResource = resource.getProject().findMember(filePath, false);
		return resource.equals(fileResource);
	}

}
