package com.thinkbiganalytics.metadata.api.feed.security;

/*-
 * #%L
 * thinkbig-operational-metadata-jpa
 * %%
 * Copyright (C) 2017 ThinkBig Analytics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


import com.thinkbiganalytics.metadata.api.feed.Feed;
import com.thinkbiganalytics.metadata.api.feed.OpsManagerFeed;

import java.io.Serializable;
import java.util.UUID;


/**
 * Created by sr186054 on 9/21/17.
 */
public interface FeedOpsAclEntry {

    ID getId();

    UUID getFeedId();

    String getPrincipalName();

    PrincipalType getPrincipalType();

    OpsManagerFeed getFeed();

    public static enum PrincipalType { USER, GROUP }

    interface ID extends Serializable {

    }



}
