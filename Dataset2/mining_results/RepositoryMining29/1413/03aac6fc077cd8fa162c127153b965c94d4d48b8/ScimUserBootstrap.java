/*******************************************************************************
 *     Cloud Foundry 
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.scim.bootstrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.authentication.manager.AuthEvent;
import org.cloudfoundry.identity.uaa.authentication.manager.ExternalGroupAuthorizationEvent;
import org.cloudfoundry.identity.uaa.scim.ScimGroup;
import org.cloudfoundry.identity.uaa.scim.ScimGroupMember;
import org.cloudfoundry.identity.uaa.scim.ScimGroupMembershipManager;
import org.cloudfoundry.identity.uaa.scim.ScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.ScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.exception.MemberAlreadyExistsException;
import org.cloudfoundry.identity.uaa.scim.exception.MemberNotFoundException;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationListener;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Convenience class for provisioning user accounts from {@link UaaUser}
 * instances.
 * 
 * @author Luke Taylor
 * @author Dave Syer
 */
public class ScimUserBootstrap implements InitializingBean, ApplicationListener<AuthEvent> {

    private static final Log logger = LogFactory.getLog(ScimUserBootstrap.class);

    private final ScimUserProvisioning scimUserProvisioning;

    private final ScimGroupProvisioning scimGroupProvisioning;

    private final ScimGroupMembershipManager membershipManager;

    private boolean override = false;

    private final Collection<UaaUser> users;

    /**
     * Flag to indicate that user accounts can be updated as well as created.
     * 
     * @param override the override flag to set (default false)
     */
    public void setOverride(boolean override) {
        this.override = override;
    }

    public ScimUserBootstrap(ScimUserProvisioning scimUserProvisioning, ScimGroupProvisioning scimGroupProvisioning,
                    ScimGroupMembershipManager membershipManager, Collection<UaaUser> users) {
        Assert.notNull(scimUserProvisioning, "scimUserProvisioning cannot be null");
        Assert.notNull(scimGroupProvisioning, "scimGroupProvisioning cannont be null");
        Assert.notNull(membershipManager, "memberShipManager cannot be null");
        Assert.notNull(users, "users list cannot be null");
        this.scimUserProvisioning = scimUserProvisioning;
        this.scimGroupProvisioning = scimGroupProvisioning;
        this.membershipManager = membershipManager;
        this.users = Collections.unmodifiableCollection(users);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        for (UaaUser u : users) {
            addUser(u);
        }
    }

    protected ScimUser getScimUser(UaaUser user) {
        List<ScimUser> users = scimUserProvisioning.query("userName eq \"" + user.getUsername() + "\"" +
                " and origin eq \"" +
                (user.getOrigin() == null ? Origin.UAA : user.getOrigin()) + "\"");
        return users.isEmpty()?null:users.get(0);
    }

    /**
     * Add a user account from the properties provided.
     * 
     * @param user a UaaUser
     */
    protected void addUser(UaaUser user) {
        ScimUser scimUser = getScimUser(user);
        if (scimUser==null) {
            createNewUser(user);
        }
        else {
            if (override) {
                updateUser(scimUser, user);
            } else {
                logger.debug("Override flag not set. Not registering existing user: " + user);
            }
        }
    }

    private void updateUser(ScimUser existingUser, UaaUser updatedUser) {
        updateUser(existingUser,updatedUser,true);
    }

    private void updateUser(ScimUser existingUser, UaaUser updatedUser, boolean updateGroups) {
        String id = existingUser.getId();
        logger.debug("Updating user account: " + updatedUser + " with SCIM Id: " + id);
        if (updateGroups) {
            logger.debug("Removing existing group memberships ...");
            Set<ScimGroup> existingGroups = membershipManager.getGroupsWithMember(id, true);

            for (ScimGroup g : existingGroups) {
                removeFromGroup(id, g.getDisplayName());
            }
        }

        final ScimUser newScimUser = convertToScimUser(updatedUser);
        newScimUser.setVersion(existingUser.getVersion());
        scimUserProvisioning.update(id, newScimUser);
        if (updateGroups) {
            Collection<String> newGroups = convertToGroups(updatedUser.getAuthorities());
            logger.debug("Adding new groups " + newGroups);
            addGroups(id, newGroups);
            scimUserProvisioning.changePassword(id, null, updatedUser.getPassword());
        }
    }

    private void createNewUser(UaaUser user) {
        logger.debug("Registering new user account: " + user);
        ScimUser newScimUser = scimUserProvisioning.createUser(convertToScimUser(user), user.getPassword());
        addGroups(newScimUser.getId(), convertToGroups(user.getAuthorities()));
    }

    private void addGroups(String scimUserid, Collection<String> groups) {
        for (String group : groups) {
            addToGroup(scimUserid, group);
        }
    }

    @Override
    public void onApplicationEvent(AuthEvent event) {
        if (event instanceof ExternalGroupAuthorizationEvent) {
            ExternalGroupAuthorizationEvent exEvent = (ExternalGroupAuthorizationEvent)event;
            //delete previous membership relation ships
            String origin = exEvent.getUser().getOrigin();
            if (!Origin.UAA.equals(origin)) {//only delete non UAA relationships
                membershipManager.delete("member_id eq \""+event.getUser().getId()+"\" and origin eq \""+origin+"\"");
            }
            for (GrantedAuthority authority : exEvent.getExternalAuthorities()) {
                addToGroup(exEvent.getUser().getId(), authority.getAuthority(), exEvent.getUser().getOrigin(), exEvent.isAddGroups());
            }
            //update the user itself
            ScimUser user = getScimUser(event.getUser());
            updateUser(user, event.getUser(), false);
        } else {
            addUser(event.getUser());
        }
    }

    private void addToGroup(String scimUserId, String gName) {
        addToGroup(scimUserId,gName,Origin.UAA, true);
    }

    private void addToGroup(String scimUserId, String gName, String origin, boolean addGroup) {
        if (!StringUtils.hasText(gName)) {
            return;
        }
        logger.debug("Adding to group: " + gName);
        List<ScimGroup> g = scimGroupProvisioning.query(String.format("displayName eq \"%s\"", gName));
        ScimGroup group;
        if ((g == null || g.isEmpty()) && (!addGroup)) {
            logger.debug("No group found with name:"+gName+". Group membership will not be added.");
            return;
        } else if (g == null || g.isEmpty()) {
            group = new ScimGroup(gName);
            group = scimGroupProvisioning.create(group);
        } else {
            group = g.get(0);
        }
        try {
            ScimGroupMember groupMember = new ScimGroupMember(scimUserId);
            groupMember.setOrigin(origin);
            membershipManager.addMember(group.getId(), groupMember);
        } catch (MemberAlreadyExistsException ex) {
            // do nothing
        }
    }

    private void removeFromGroup(String scimUserId, String gName) {
        if (!StringUtils.hasText(gName)) {
            return;
        }
        logger.debug("Removing membership of group: " + gName);
        List<ScimGroup> g = scimGroupProvisioning.query(String.format("displayName eq \"%s\"", gName));
        ScimGroup group;
        if (g == null || g.isEmpty()) {
            return;
        }
        else {
            group = g.get(0);
        }
        try {
            membershipManager.removeMemberById(group.getId(), scimUserId);
        } catch (MemberNotFoundException ex) {
            // do nothing
        }
    }

    /**
     * Convert UaaUser to SCIM data.
     * Bootstrapped users are verified by default
     */
    private ScimUser convertToScimUser(UaaUser user) {
        ScimUser scim = new ScimUser(user.getId(), user.getUsername(), user.getGivenName(), user.getFamilyName());
        scim.addEmail(user.getEmail());
        scim.setOrigin(user.getOrigin());
        scim.setExternalId(user.getExternalId());
        scim.setVerified(true);
        return scim;
    }

    /**
     * Convert authorities to group names.
     */
    private Collection<String> convertToGroups(List<? extends GrantedAuthority> authorities) {
        List<String> groups = new ArrayList<String>();
        for (GrantedAuthority authority : authorities) {
            groups.add(authority.toString());
        }
        return groups;
    }
}
