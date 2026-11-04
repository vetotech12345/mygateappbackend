-- Reference data for RBAC. Loaded into memory at startup by RolePermissionRegistry.
-- Codes must match com.mygateapp.security.Role and com.mygateapp.security.Permission.

INSERT INTO roles (code, description, scope) VALUES
    ('SUPER_ADMIN',   'Platform administrator',                     'PLATFORM'),
    ('SOCIETY_ADMIN', 'Administrator of one or more societies',     'SOCIETY'),
    ('RESIDENT',      'Owner or tenant living in a flat',           'SOCIETY'),
    ('GUARD',         'Security guard operating a society gate',    'SOCIETY');

INSERT INTO permissions (code, description) VALUES
    ('PLATFORM_SOCIETY_CREATE',  'Create societies'),
    ('PLATFORM_SOCIETY_STATUS',  'Activate / deactivate societies and manage subscriptions'),
    ('PLATFORM_ADMIN_MANAGE',    'Create and remove society administrators'),
    ('PLATFORM_STATS_VIEW',      'View platform-level statistics'),
    ('PLATFORM_SETTINGS_MANAGE', 'Manage platform configuration'),
    ('PLATFORM_USER_STATUS',     'Activate / deactivate user accounts'),
    ('SOCIETY_VIEW',             'View society details and configuration'),
    ('SOCIETY_UPDATE',           'Update society details and configuration'),
    ('STRUCTURE_MANAGE',         'Manage towers, wings and flats'),
    ('RESIDENT_VIEW',            'View residents of the society'),
    ('RESIDENT_MANAGE',          'Create, approve, update and end resident memberships'),
    ('GUARD_MANAGE',             'Manage security guards and their assignments'),
    ('GATE_MANAGE',              'Manage society gates'),
    ('VISITOR_LOG_VIEW',         'View all visitor records of the society'),
    ('DELIVERY_LOG_VIEW',        'View all delivery records of the society'),
    ('STAFF_VIEW',               'View all domestic staff of the society'),
    ('STAFF_MANAGE',             'Register and manage domestic staff'),
    ('COMPLAINT_MANAGE',         'Assign, comment on and change status of complaints'),
    ('NOTICE_MANAGE',            'Create, edit, publish and delete notices'),
    ('NOTICE_READ',              'Read notices addressed to the user'),
    ('REPORT_VIEW',              'View society dashboards and reports'),
    ('AUDIT_VIEW',               'View the society audit log'),
    ('FLAT_VIEW_OWN',            'View own flat details'),
    ('RESIDENT_REQUEST',         'Request a co-resident or tenant for an owned flat'),
    ('FAMILY_MANAGE',            'Manage own family members'),
    ('VISITOR_INVITE',           'Pre-approve visitors for own flat'),
    ('VISITOR_DECIDE',           'Approve or reject visitors for own flat'),
    ('VISITOR_VIEW_OWN',         'View visitors of own flat'),
    ('DELIVERY_DECIDE',          'Approve or reject deliveries for own flat'),
    ('DELIVERY_VIEW_OWN',        'View deliveries of own flat'),
    ('STAFF_VIEW_OWN',           'View domestic staff assigned to own flat'),
    ('COMPLAINT_CREATE',         'Raise complaints for own flat'),
    ('COMPLAINT_VIEW_OWN',       'View complaints of own flat'),
    ('GATE_VISITOR_OPERATE',     'Register visitors and record their entry / exit'),
    ('GATE_DELIVERY_OPERATE',    'Register deliveries and record their entry / exit'),
    ('GATE_STAFF_OPERATE',       'Record domestic staff check-in / check-out'),
    ('GATE_LOG_VIEW',            'View gate logs of the assigned society');

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'SUPER_ADMIN', code FROM permissions
WHERE code LIKE 'PLATFORM\_%'
   OR code IN ('SOCIETY_VIEW', 'SOCIETY_UPDATE', 'STRUCTURE_MANAGE', 'RESIDENT_VIEW', 'RESIDENT_MANAGE', 'GUARD_MANAGE',
               'GATE_MANAGE', 'VISITOR_LOG_VIEW', 'DELIVERY_LOG_VIEW', 'STAFF_VIEW', 'STAFF_MANAGE', 'COMPLAINT_MANAGE',
               'NOTICE_MANAGE', 'NOTICE_READ', 'REPORT_VIEW', 'AUDIT_VIEW');

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'SOCIETY_ADMIN', code FROM permissions
WHERE code IN ('SOCIETY_VIEW', 'SOCIETY_UPDATE', 'STRUCTURE_MANAGE', 'RESIDENT_VIEW', 'RESIDENT_MANAGE', 'GUARD_MANAGE',
               'GATE_MANAGE', 'VISITOR_LOG_VIEW', 'DELIVERY_LOG_VIEW', 'STAFF_VIEW', 'STAFF_MANAGE', 'COMPLAINT_MANAGE',
               'NOTICE_MANAGE', 'NOTICE_READ', 'REPORT_VIEW', 'AUDIT_VIEW');

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'RESIDENT', code FROM permissions
WHERE code IN ('FLAT_VIEW_OWN', 'RESIDENT_REQUEST', 'FAMILY_MANAGE', 'VISITOR_INVITE', 'VISITOR_DECIDE', 'VISITOR_VIEW_OWN',
               'DELIVERY_DECIDE', 'DELIVERY_VIEW_OWN', 'STAFF_VIEW_OWN', 'COMPLAINT_CREATE', 'COMPLAINT_VIEW_OWN', 'NOTICE_READ');

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'GUARD', code FROM permissions
WHERE code IN ('GATE_VISITOR_OPERATE', 'GATE_DELIVERY_OPERATE', 'GATE_STAFF_OPERATE', 'GATE_LOG_VIEW', 'NOTICE_READ');

INSERT INTO platform_settings (setting_key, setting_value, description, updated_at) VALUES
    ('support.email',              'support@example.com', 'Support contact shown to society admins', now()),
    ('society.default.max-invite-hours', '168',           'Default invite validity for new societies', now());
