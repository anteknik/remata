import org.remata.elements.messages.SessionMessages
import org.remata.elements.util.RandomUtil
import org.remata.portofolio.di.Inject
import org.remata.portofolio.logic.SecurityLogic
import org.remata.portofolio.model.database.Database
import org.remata.portofolio.model.database.DatabaseLogic
import org.remata.portofolio.model.database.Table
import org.remata.portofolio.modules.DatabaseModule
import org.remata.portofolio.persistence.Persistence
import org.remata.portofolio.persistence.QueryUtils
import org.remata.portofolio.reflection.TableAccessor
import org.remata.portofolio.util.PkHelper
import org.apache.commons.lang.StringUtils
import org.apache.shiro.crypto.hash.DefaultHashService
import org.apache.shiro.crypto.hash.HashService
import org.apache.shiro.crypto.hash.format.HashFormat
import org.hibernate.Criteria
import org.hibernate.Query
import org.hibernate.Session
import org.hibernate.criterion.Order
import org.hibernate.criterion.Projections
import org.hibernate.criterion.Restrictions
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.remata.portofolio.shiro.*
import org.apache.shiro.authc.*

public class Security extends AbstractPortofinoRealm {

    public static final Logger logger = LoggerFactory.getLogger(Security.class);

    protected String databaseName = "$databaseName";

    protected String userTableEntityName = "$userTableEntityName";
    protected String userIdProperty = "$userIdProperty";
    protected String userNameProperty = "$userNameProperty";
    protected String userEmailProperty = "$userEmailProperty";
    protected String userTokenProperty = "$userTokenProperty";
    protected String passwordProperty = "$passwordProperty";

    protected String groupTableEntityName = "$groupTableEntityName";
    protected String groupIdProperty = "$groupIdProperty";
    protected String groupNameProperty = "$groupNameProperty";

    protected String userGroupTableEntityName = "$userGroupTableEntityName";
    protected String groupLinkProperty = "$groupLinkProperty";
    protected String userLinkProperty = "$userLinkProperty";

    protected String adminGroupName = "$adminGroupName";

    ////Password Hashing
    protected int hashIterations = $hashIterations;
    protected String hashAlgorithm = $hashAlgorithm;
    protected HashFormat hashFormat = $hashFormat;

    @Inject(DatabaseModule.PERSISTENCE)
    Persistence persistence;

    public Security() {
        if(!"plaintext".equals(hashAlgorithm)) {
            HashService hashService = new DefaultHashService();
            hashService.setHashIterations(hashIterations);
            hashService.setHashAlgorithmName(hashAlgorithm);
            hashService.setGeneratePublicSalt(false); //to enable salting, set this to true and/or call setPrivateSalt
            //Also, if using a public salt, you should uncomment the following, otherwise different Security.groovy
            //instances will fail to match credentials stored in the database
            //hashFormat = new Shiro1CryptFormat()
            setup(hashService, hashFormat);
        }
    }

    @Override
    protected Collection<String> loadAuthorizationInfo(Serializable principal) {
        def groups = []
        if(StringUtils.isEmpty(userGroupTableEntityName) || StringUtils.isEmpty(groupTableEntityName)) {
            //Groups were not configured with the wizard. Please place here your own authorization logic.

            /////////////////////////////////////////////////////////////////
            //NB admin is hardcoded for the wizard to work - remove it in production!
            /////////////////////////////////////////////////////////////////
            if("admin".equals(principal) ||
               (principal instanceof Map && "admin".equals(principal[userNameProperty]))) {
                logger.warn("Generated Security.groovy is using the hardcoded 'admin' user; " +
                            "remember to disable it in production!")
                groups.add(SecurityLogic.getAdministratorsGroup(portofinoConfiguration));
            }
            /////////////////////////////////////////////////////////////////
        } else {
            //Load groups from the database
            assert principal instanceof Map;
            Session session = persistence.getSession(databaseName);
            def queryString = """
                select distinct g.${groupNameProperty}
                from ${groupTableEntityName} g, ${userGroupTableEntityName} ug, ${userTableEntityName} u
                where g.${groupIdProperty} = ug.${groupLinkProperty}
                and ug.${userLinkProperty} = u.${userIdProperty}
                and u.${userIdProperty} = :userId
            """;
            def query = session.createQuery(queryString);
            query.setParameter("userId", principal[userIdProperty]);
            groups.addAll(query.list());

            if(!StringUtils.isEmpty(adminGroupName) && groups.contains(adminGroupName)) {
                groups.add(SecurityLogic.getAdministratorsGroup(portofinoConfiguration));
            }
        }
        return groups;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
        return loadAuthenticationInfo(token);
    }

    AuthenticationInfo loadAuthenticationInfo(UsernamePasswordToken usernamePasswordToken) {
        String userName = usernamePasswordToken.username;
        String password = new String(usernamePasswordToken.password);

        if(StringUtils.isEmpty(userTableEntityName)) {
            //Users were not configured with the wizard. Please place here your own authentication logic.

            /////////////////////////////////////////////////////////////////
            //NB admin is hardcoded for the wizard to work - remove it in production!
            /////////////////////////////////////////////////////////////////
            if("admin".equals(userName) && "admin".equals(password)) {
                logger.warn("Generated Security.groovy is using the hardcoded 'admin' user; " +
                            "remember to disable it in production!")
                SessionMessages.addWarningMessage("Generated Security.groovy is using the hardcoded 'admin' user; " +
                                                  "remember to disable it in production!")
                SimpleAuthenticationInfo info =
                        new SimpleAuthenticationInfo(userName, encryptPassword(password), getName());
                return info;
            }
            /////////////////////////////////////////////////////////////////
        }

        Session session = persistence.getSession(databaseName);
        org.hibernate.Criteria criteria = session.createCriteria(userTableEntityName);
        criteria.add(Restrictions.eq(userNameProperty, userName));

        List result = criteria.list();

        if (result.size() == 1) {
            def user = result.get(0);

            SimpleAuthenticationInfo info =
                    new SimpleAuthenticationInfo(user, user[passwordProperty], getName());
            return info;
        } else {
            throw new IncorrectCredentialsException("Login failed");
        }
    }

    AuthenticationInfo loadAuthenticationInfo(PasswordResetToken token) {
        if(StringUtils.isEmpty(userTokenProperty)) {
            throw new AuthenticationException("User token property is not configured; password reset is not supported by this application.");
        }

        Session session = persistence.getSession(databaseName);
        Criteria criteria = session.createCriteria(userTableEntityName);
        criteria.add(Restrictions.eq(userTokenProperty, token.principal));

        List result = criteria.list();

        if (result.size() == 1) {
            def hashedPassword = encryptPassword(token.newPassword)
            def user = result.get(0);
            user[userTokenProperty] = null; //Consume token
            user[passwordProperty] = hashedPassword;
            session.update(userTableEntityName, (Object) user);
            session.transaction.commit();
            SimpleAuthenticationInfo info =
                new SimpleAuthenticationInfo(user, hashedPassword, getName());
            return info;
        } else {
            throw new IncorrectCredentialsException("Invalid token");
        }
    }

    AuthenticationInfo loadAuthenticationInfo(SignUpToken token) {
        if(StringUtils.isEmpty(userTokenProperty)) {
            throw new AuthenticationException(
                    "User token property is not configured; self registration is not supported by this application.");
        }

        Session session = persistence.getSession(databaseName);
        Criteria criteria = session.createCriteria(userTableEntityName);
        criteria.add(Restrictions.eq(userTokenProperty, token.principal));

        List result = criteria.list();

        if (result.size() == 1) {
            def user = result.get(0);
            user[userTokenProperty] = null; //Consume token
            session.update(userTableEntityName, (Object) user);
            session.transaction.commit();
            SimpleAuthenticationInfo info =
                new SimpleAuthenticationInfo(user, encryptPassword(token.credentials), getName());
            return info;
        } else {
            throw new IncorrectCredentialsException("Invalid token");
        }
    }

    @Override
    boolean supports(AuthenticationToken token) {
        if(token instanceof PasswordResetToken || token instanceof SignUpToken) {
            return !StringUtils.isEmpty(userTokenProperty);
        }
        return super.supports(token);
    }

    @Override
    void changePassword(Serializable user, String oldPassword, String newPassword) {
        if(StringUtils.isEmpty(userTableEntityName)) {
            throw new UnsupportedOperationException("Users table is not configured");
        }
        Session session = persistence.getSession(databaseName);
        def savedUser = session.load(userTableEntityName, user[userIdProperty] as Serializable);
        if(savedUser == null) {
            throw new UnknownAccountException("User has been deleted");
        } else if(!passwordService.passwordsMatch(oldPassword, savedUser[passwordProperty] as String)) {
            throw new IncorrectCredentialsException("Wrong password");
        } else {
            savedUser[passwordProperty] = encryptPassword(newPassword);
            session.transaction.commit();
        }
    }

    String encryptPassword(String password) {
        return passwordService.encryptPassword(password);
    }

    Map<Serializable, String> getUsers() {
        if(StringUtils.isEmpty(userTableEntityName)) {
            //Users were not configured with the wizard.
            return Collections.emptyMap();
        }

        def users = new LinkedHashMap();
        Session session = persistence.getSession(databaseName);
        Query query = session.createQuery(
                "select ${userIdProperty}, ${userNameProperty} from ${userTableEntityName} order by ${userNameProperty}");
        for(Object[] user : query.list()) {
            users.put(user[0], user[1]);
        }
        return users;
    }

    Serializable getUserById(String encodedId) {
        if(StringUtils.isEmpty(userTableEntityName)) {
            //Users were not configured with the wizard.
            return encodedId;
        }

        TableAccessor accessor = getUserClassAccessor();
        PkHelper pkHelper = new PkHelper(accessor);
        Serializable id = pkHelper.getPrimaryKey(encodedId);
        Session session = persistence.getSession(databaseName);
        return (Serializable) QueryUtils.getObjectByPk(session, accessor, id);
    }

    TableAccessor getUserClassAccessor() {
        Database database =
            DatabaseLogic.findDatabaseByName(persistence.model, databaseName);
        Table table =
            DatabaseLogic.findTableByEntityName(database, userTableEntityName);
        def accessor = new TableAccessor(table);
        return accessor;
    }

    Serializable getUserByEmail(String email) {
        if(StringUtils.isEmpty(userEmailProperty)) {
            throw new UnsupportedOperationException("Email property not configured.");
        }
        Session session = persistence.getSession(databaseName);
        def criteria = session.createCriteria(userTableEntityName);
        criteria.add(Restrictions.eq(userEmailProperty, email));
        return (Serializable) criteria.uniqueResult();
    }

    @Override
    String getUserPrettyName(Serializable user) {
        if(StringUtils.isEmpty(userNameProperty)) {
            return user.toString();
        }
        return user[userNameProperty];
    }

    Serializable getUserId(Serializable user) {
        if(StringUtils.isEmpty(userIdProperty)) {
            return user.toString();
        }
        return (Serializable) user[userIdProperty];
    }

    @Override
    String generateOneTimeToken(Serializable user) {
        if(StringUtils.isEmpty(userTokenProperty)) {
            throw new UnsupportedOperationException("Token property not configured.");
        }
        Session session = persistence.getSession(databaseName);
        user = (Serializable) session.get(userTableEntityName, user[userIdProperty]);
        String token = RandomUtil.createRandomId(20);
        user[userTokenProperty] = token;
        session.update(userTableEntityName, (Object) user);
        session.transaction.commit();
        return token;
    }

    String saveSelfRegisteredUser(Object user) throws RegistrationException {
        if(StringUtils.isEmpty(userTokenProperty)) {
            throw new UnsupportedOperationException("Token property not configured.");
        }
        User theUser = (User) user;
        Session session = persistence.getSession(databaseName);
        Map persistentUser = new HashMap();
        persistentUser[userNameProperty] = theUser.username;
        persistentUser[passwordProperty] = encryptPassword(theUser.password);
        if(!StringUtils.isEmpty(userEmailProperty)) {
            persistentUser[userEmailProperty] = theUser.email;
        }

        String token = RandomUtil.createRandomId(20);
        persistentUser[userTokenProperty] = token;

        try {
            session.save(userTableEntityName, (Object) persistentUser);
            session.flush();
        } catch (ConstraintViolationException e) {
            throw new ExistingUserException(e);
        }
        session.transaction.commit();
        return token;
    }

    Set<String> getGroups() {
        def groups = super.getGroups()

        if(!StringUtils.isEmpty(groupTableEntityName)) {
            Session session = persistence.getSession(databaseName)
            def criteria = session.createCriteria(groupTableEntityName)
            criteria.projection = Projections.property(groupNameProperty)
            criteria.addOrder(Order.asc(groupNameProperty))
            for(x in criteria.list()) {
                groups.add(String.valueOf(x))
            }
        }
        return groups;
    }

}

