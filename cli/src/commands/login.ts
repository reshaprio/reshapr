/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import http from 'http';
import url from 'url';
import open from 'open';
import getPort, {portNumbers} from 'get-port';

import { Command } from "commander";
import inquirer from 'inquirer';
import { Logger } from "../utils/logger.js";
import { ConfigUtil } from '../utils/config.js';
import { CLI_LABEL } from '../constants.js';

export const loginCommand = new Command('login')
  .description(`Login to ${CLI_LABEL}`)
  .option('-u, --username <username>', `Your ${CLI_LABEL} username`)
  .option('-p, --password <password>', `Your ${CLI_LABEL} password`)
  .option('-o, --org <org>', `Your ${CLI_LABEL} organization name`)
  .option('-s, --server <server>', `Your ${CLI_LABEL} Control Plane URL`, 'https://try.reshapr.io')
  .option('-k, --insecure', 'Skip SSL certificate validation')
  .option('--password-stdin', 'Read password from stdin')
  .action(async (options) => {
    // Validate that --password and --password-stdin are not both provided.
    if (options.password && options.passwordStdin) {
      Logger.error('--password and --password-stdin are mutually exclusive.');
      process.exit(1);
    }

    // If --password-stdin is set, read the password from stdin.
    if (options.passwordStdin) {
      if (process.stdin.isTTY) {
        Logger.error('Error: --password-stdin requires piped input (e.g. echo "password" | reshapr login --password-stdin -u user)');
        process.exit(1);
      }
      options.password = await readFromStdin();
      if (!options.password) {
        Logger.error('Error: password is empty when reading from stdin.');
        process.exit(1);
      }
    }
    // First validate server URL and fetch server configuration.
    const configResponse = await fetch(`${options.server}/api/config`, {
      method: 'GET'
    }).catch((err) => {
      Logger.error(`Failed to connect to the server. Check URL. (${err?.cause?.code ?? err?.message})`);
      process.exit(1);
    });

    const configData = await configResponse.json().catch(err => {
      Logger.error('Failed to parse server configuration: ' + err.message);
      process.exit(1);
    });

    if (configData.mode === 'on-premises') {
      if (configData.oidcEnabled) {
        await handleOidcLogin(options);
      } else { 
        await handleLoginPasswordLogin(options);
      }
    } else if (configData.mode === 'saas') {
      await handleSaaSLogin(options);
    }
  });

  function readFromStdin(): Promise<string> {
    return new Promise((resolve, reject) => {
      let data = '';
      process.stdin.setEncoding('utf8');
      process.stdin.on('data', (chunk) => { data += chunk; });
      process.stdin.on('end', () => { resolve(data.trim()); });
      process.stdin.on('error', (err) => { reject(err); });
    });
  }

  async function handleLoginPasswordLogin(options: {username?: string; password?: string; server: string; insecure?: boolean}) {
    // Handle on-premises login logic here if needed.
    if (!options.username) {
      const username = await inquirer.prompt({
        type: 'input',
        name: 'username',
        message: `Enter your ${CLI_LABEL} username:`,
        validate: (input) => {
          if (!input) {
            return 'Username is required';
          }
          return true;
        }
      });
      options.username = username.username;
    }
    if (!options.password) {
      const password = await inquirer.prompt({
        type: 'password',
        name: 'password',
        message: `Enter your ${CLI_LABEL} password:`,
        validate: (input) => {
          if (!input) {
            return 'Password is required';
          }
          return true;
        }
      });
      options.password = password.password;
    }
    // Captured as a local const + guard so the narrowed (non-undefined) type survives into the closure
    // below (narrowing of a reassigned object property does not survive past the `if` block above).
    const username = options.username;
    if (!username) {
      Logger.error('Username is required.');
      process.exit(1);
    }

    // Here you call a login function to authenticate the user.
    Logger.info(`Logging in to ${CLI_LABEL} at ${options.server}...`);
    const response = await fetch(`${options.server}/auth/login/reshapr`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        username,
        password: options.password
      })
    });

    if (!response.ok) {
      Logger.error('Login failed: ' + response.statusText);
      process.exit(1);
    }
    response.text().then(data => {
      Logger.success('Login successful!');
      Logger.info(`Welcome, ${username}!`);
      // Here you would typically save the authentication token or session.
      const config = {
        username,
        server: options.server,
        insecure: options.insecure,
        token: data // Assuming the response contains a token.
      };
      ConfigUtil.writeConfig(config);
    }).catch(err => {
      Logger.error('Error parsing response: ' + err.message);
      process.exit(1);
    });
  }

  async function handleOidcLogin(options: {username?: string; password?: string; server: string; insecure?: boolean}) {
    // Use the control plane's OIDC login flow for authentication.
    await handleAuthenticationBrowserFlow(`${options.server}/auth/login/oidc`, options);
  }

  async function handleSaaSLogin(options: {username?: string; password?: string; server: string; insecure?: boolean}) {
    // Use the Saas /cli/login endpoint flow for authentication.
    await handleAuthenticationBrowserFlow(`${options.server}/cli/login`, options);
  }

  async function handleAuthenticationBrowserFlow(authServerUrl: string, options: {username?: string; password?: string; server: string; insecure?: boolean}) {
    // Prepare a token for reception.
    let token: string | null = null;

    // Start a lightweight web server to receive the OAuth2 callback.
    const server = http.createServer((req, res) => {
      // Parse the URL and extract query parameters.
      const parsedUrl = url.parse(req.url || '', true);
      const query = parsedUrl.query;

      // The control plane may redirect back with ?error=... (e.g. access_denied
      // when the IDP guard-access policy blocks the user). Surface it nicely.
      if (query.error && (query.error as string).length > 0) {
        const errorCode = query.error as string;
        const {title, message, terminalMessage} = describeAuthenticationError(errorCode);

        Logger.error(terminalMessage);

        res.writeHead(403, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(`
          <html>
            <head><meta charset="utf-8"></head>
            <body style="font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #f8fafc;">
              <div style="text-align: center; max-width: 480px; padding: 0 1.5rem;">
                <h1 style="color: #dc2626;">&#10007; ${title}</h1>
                <p style="color: #475569;">${message}</p>
                <p style="color: #94a3b8; margin-top: 1.5rem; font-size: 0.9rem;">You can close this window and return to your terminal.</p>
              </div>
            </body>
          </html>
        `);

        setTimeout(() => {
          server.close();
          process.exit(1);
        }, 500);
        return;
      }

      if (query.token && (query.token as string).length > 0) {
        token = query.token as string;

        // The SaaS also sends the control plane URL for subsequent CLI calls.
        const ctrlUrl = (query.ctrl_url as string) || options.server;

        Logger.success('Login successful!');

        // Decode the JWT payload to extract username and org.
        try {
          const tokenPayload = token.split('.')[1];
          const decodedPayload = Buffer.from(tokenPayload, 'base64').toString('utf8');
          const payload = JSON.parse(decodedPayload);
          const username = payload.sub || 'unknown';
          const org = payload.org || '';

          Logger.info(`Welcome, ${username}!`);
          if (org) {
            Logger.info(`Organization: ${org}`);
          }

          // Save the configuration — server points to the control plane, not the SaaS.
          const config = {
            username: username,
            server: ctrlUrl,
            insecure: options.insecure,
            token: token
          };
          ConfigUtil.writeConfig(config);
        } catch (err) {
          Logger.warn('Could not decode token payload: ' + err);
          // Still save the config with what we have.
          const config = {
            username: 'unknown',
            server: ctrlUrl,
            insecure: options.insecure,
            token: token
          };
          ConfigUtil.writeConfig(config);
        }

        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(`
          <html>
            <head><meta charset="utf-8"></head>
            <body style="font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #f8fafc;">
              <div style="text-align: center;">
                <h1 style="color: #16a34a;">&#10003; Login successful!</h1>
                <p style="color: #64748b;">You can close this window and return to your terminal.</p>
              </div>
            </body>
          </html>
        `);

        // Close the server and exit after a short delay.
        setTimeout(() => {
          server.close();
          process.exit(0);
        }, 500);
      } else {
        // We may receive other requests to this server that don't have a token, so just respond with an error page.
        if (!token) {
          Logger.error('Login failed: No token received.');
          res.writeHead(400, { 'Content-Type': 'text/html; charset=utf-8' });
          res.end(`
            <html>
              <head><meta charset="utf-8"></head>
              <body style="font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #f8fafc;">
                <div style="text-align: center;">
                  <h1 style="color: #dc2626;">&#10007; Login failed</h1>
                  <p style="color: #64748b;">No token received. Please try again.</p>
                </div>
              </body>
            </html>
          `);
        }
      }
    });

    server.on('error', (err: Error) => {
      Logger.error('Failed to start local server for authentication: ' + err.message);
      process.exit(1);
    });

    // Find an available port.
    const localPort = await getPort({ port: portNumbers(5556, 5599) });

    server.listen(localPort, () => {
      Logger.info(`Listening for authentication callback on http://localhost:${localPort}`);
    });

    // Open the browser to the SaaS CLI login page.
    const loginUrl = `${authServerUrl}?redirect_uri=http://localhost:${localPort}`;
    Logger.info(`Opening browser: ${loginUrl}`);

    // Opens the URL in the default browser.
    await open(loginUrl, { wait: false });
  }

  function describeAuthenticationError(errorCode: string): { title: string; message: string; terminalMessage: string } {
    switch (errorCode) {
      case 'access_denied':
        return {
          title: 'Access denied',
          message: 'Your account is not allowed to access reShapr. Please contact your administrator to request access.',
          terminalMessage: 'Login failed: your account is not allowed to access reShapr. Please contact your administrator to request access.'
        };
      case 'missing_token':
        return {
          title: 'Login failed',
          message: 'No token received from the authentication server. Please try again.',
          terminalMessage: 'Login failed: no token received from the authentication server.'
        };
      case 'invalid_token':
        return {
          title: 'Login failed',
          message: 'The token received from the authentication server is invalid. Please try again.',
          terminalMessage: 'Login failed: the token received from the authentication server is invalid.'
        };
      default:
        return {
          title: 'Login failed',
          message: `Authentication returned an error: ${errorCode}.`,
          terminalMessage: `Login failed: ${errorCode}.`
        };
    }
  }