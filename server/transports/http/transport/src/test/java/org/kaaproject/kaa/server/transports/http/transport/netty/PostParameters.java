/*
 * Copyright 2014-2016 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * 
 */
package org.kaaproject.kaa.server.transports.http.transport.netty;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * @author Andrey Panasenko <apanasenko@cybervisiontech.com>
 *
 */
public class PostParameters {

    private StringBuffer query;

    public PostParameters() {
        query = new StringBuffer();
    }

    public synchronized PostParameters add(Object name, Object value)
                throws UnsupportedEncodingException {
        if (!query.toString().trim().equals("")) {
            query.append("&");
        }
        query.append(URLEncoder.encode(name.toString(), "UTF-8"));
        query.append("=");
        query.append(URLEncoder.encode(value.toString(), "UTF-8"));
        return this;
    }

    public String toString() {
        return query.toString();
    }
}
