/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.ctrl.rest.v1;

import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.UserRepository;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrganizationResourceTest {

   private OrganizationResource organizationResource;
   private String currentUsername;
   private User foundUserByEmail;
   private Organization foundOrgByName;

   @BeforeEach
   void setup() {
      UserRepository stubUserRepository = new UserRepository() {
         @Override
         public User findByEmail(String email) {
            return foundUserByEmail;
         }
         @Override
         public void persistAndFlush(User entity) {
            // Do nothing
         }
      };

      OrganizationRepository stubOrganizationRepository = new OrganizationRepository() {
         @Override
         public Organization findByName(String name) {
            return foundOrgByName;
         }
      };

      organizationResource = new OrganizationResource(stubUserRepository, stubOrganizationRepository);
   }

   @Test
   void testGetMembersAsOwner() {
      currentUsername = "owner";

      User ownerUser = new User();
      ownerUser.username = "owner";

      User memberUser = new User();
      memberUser.username = "member";
      memberUser.email = "member@test.com";

      Organization org = new Organization();
      org.name = "my-org";
      org.owner = ownerUser;
      org.members = List.of(ownerUser, memberUser);

      foundOrgByName = org;

      Response response = organizationResource.getMembers(createStubIdentity(), "my-org");
      assertEquals(200, response.getStatus());

      List<MemberDTO> returnedMembers = (List<MemberDTO>) response.getEntity();
      assertEquals(2, returnedMembers.size());
      assertEquals("owner", returnedMembers.get(0).username());
      assertEquals("member", returnedMembers.get(1).username());
   }

   @Test
   void testGetMembersAsNonOwner() {
      currentUsername = "not-owner";

      User ownerUser = new User();
      ownerUser.username = "owner";

      Organization org = new Organization();
      org.name = "my-org";
      org.owner = ownerUser;
      org.members = List.of(ownerUser);

      foundOrgByName = org;

      Response response = organizationResource.getMembers(createStubIdentity(), "my-org");
      assertEquals(403, response.getStatus());
   }

   @Test
   void testAddMemberAsOwner() {
      currentUsername = "owner";

      User ownerUser = new User();
      ownerUser.username = "owner";

      Organization org = new Organization();
      org.name = "my-org";
      org.owner = ownerUser;

      User newMember = new User();
      newMember.username = "new-member";
      newMember.email = "new@test.com";
      newMember.organizations = new ArrayList<>();

      foundOrgByName = org;
      foundUserByEmail = newMember;

      MemberRequestDTO request = new MemberRequestDTO("new@test.com");
      Response response = organizationResource.addMember(createStubIdentity(), "my-org", request);
      
      assertEquals(200, response.getStatus());
      assertEquals(1, newMember.organizations.size());
      assertEquals(org, newMember.organizations.get(0));
   }

   @Test
   void testAddMemberNotFound() {
      currentUsername = "owner";

      User ownerUser = new User();
      ownerUser.username = "owner";

      Organization org = new Organization();
      org.name = "my-org";
      org.owner = ownerUser;

      foundOrgByName = org;
      foundUserByEmail = null;

      MemberRequestDTO request = new MemberRequestDTO("nonexistent@test.com");
      Response response = organizationResource.addMember(createStubIdentity(), "my-org", request);
      
      assertEquals(400, response.getStatus());
   }

   @Test
   void testRemoveMemberAsOwner() {
      currentUsername = "owner";

      User ownerUser = new User();
      ownerUser.username = "owner";

      Organization org = new Organization();
      org.name = "my-org";
      org.owner = ownerUser;

      User existingMember = new User();
      existingMember.username = "member";
      existingMember.email = "member@test.com";
      existingMember.organizations = new ArrayList<>(List.of(org));

      foundOrgByName = org;
      foundUserByEmail = existingMember;

      Response response = organizationResource.removeMember(createStubIdentity(), "my-org", "member@test.com");
      
      assertEquals(204, response.getStatus());
      assertEquals(0, existingMember.organizations.size());
   }

   private SecurityIdentity createStubIdentity() {
      return (SecurityIdentity) java.lang.reflect.Proxy.newProxyInstance(
            OrganizationResourceTest.class.getClassLoader(),
            new Class<?>[]{SecurityIdentity.class},
            (proxy, method, args) -> {
               if ("getPrincipal".equals(method.getName())) {
                  return (Principal) () -> currentUsername;
               }
               return null;
            }
      );
   }
}
