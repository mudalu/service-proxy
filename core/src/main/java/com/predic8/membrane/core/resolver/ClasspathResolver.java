/* Copyright 2013 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package com.predic8.membrane.core.resolver;

import java.io.InputStream;
import java.util.List;

import com.google.common.collect.Lists;

public class ClasspathResolver implements SchemaResolver {

	@Override
	public List<String> getSchemas() {
		return Lists.newArrayList("classpath");
	}

	@Override
	public InputStream resolve(String url) {
		return getClass().getResourceAsStream(url.substring(10));
	}
	
	@Override
	public List<String> getChildren(String url) {
		return null;
	}
	
	@Override
	public long getTimestamp(String url) {
		return 0;
	}
	

}
