/*
 * Copyright 2020-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sample.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * @author Joe Grandja
 * @author Dmitriy Dubson
 * @since 0.0.1
 */
@Controller
public class DefaultController {

	private static final Logger LOGGER = LoggerFactory.getLogger(DefaultController.class);

	@GetMapping("/")
	public String root() {
		return "redirect:/index";
	}

	@GetMapping("/index")
	public String index() {
		return "index";
	}

	@GetMapping("/logged-out")
	public String loggedOut() {
		return "logged-out";
	}

	@GetMapping("/debug/id-token")
	@ResponseBody
	public String idToken(@AuthenticationPrincipal OidcUser oidcUser) {
		if (oidcUser == null || oidcUser.getIdToken() == null) {
			return "No OIDC id_token is available for the current user.";
		}

		String tokenValue = oidcUser.getIdToken().getTokenValue();
		LOGGER.info("Current OIDC id_token: {}", tokenValue);
		return tokenValue;
	}

}
