/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.blobstore.s3.internal

import java.util.stream.Collectors

import org.sonatype.nexus.blobstore.BlobIdLocationResolver
import org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver
import org.sonatype.nexus.blobstore.api.BlobId
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration
import org.sonatype.nexus.blobstore.api.BlobStoreException
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService
import org.sonatype.nexus.common.log.DryRunPrefix

import com.amazonaws.SdkClientException
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration.Transition
import com.amazonaws.services.s3.model.ObjectListing
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.model.S3ObjectInputStream
import com.amazonaws.services.s3.model.S3ObjectSummary
import com.amazonaws.services.s3.model.StorageClass
import spock.lang.Specification
import spock.lang.Unroll

import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.BLOB_ATTRIBUTE_SUFFIX
import static org.sonatype.nexus.blobstore.s3.internal.S3BlobStore.BLOB_CONTENT_SUFFIX

/**
 * {@link S3BlobStore} tests.
 */
class S3BlobStoreTest
    extends Specification
{

  AmazonS3Factory amazonS3Factory = Mock()

  BlobIdLocationResolver locationResolver = new DefaultBlobIdLocationResolver()

  S3Uploader uploader =  Mock()

  S3BlobStoreMetricsStore storeMetrics = Mock()

  DryRunPrefix dryRunPrefix = Mock()

  AmazonS3 s3 = Mock()

  S3BlobStore blobStore = new S3BlobStore(amazonS3Factory, locationResolver, uploader,
      storeMetrics, dryRunPrefix)

  def config = new BlobStoreConfiguration()

  def attributesContents = """\
        |#Thu Jun 01 23:10:55 UTC 2017
        |@BlobStore.created-by=admin
        |size=11
        |@Bucket.repo-name=test
        |creationTime=1496358655289
        |@BlobStore.content-type=text/plain
        |@BlobStore.blob-name=test
        |sha1=eb4c2a5a1c04ca2d504c5e57e1f88cef08c75707
      """.stripMargin()

  def setup() {
    amazonS3Factory.create(_) >> s3
    config.attributes = [s3: [bucket: 'mybucket']]
  }

  def "Get blob"() {
    given: 'A mocked S3 setup'
      def blobId = new BlobId('test')
      def attributesS3Object = mockS3Object(attributesContents)
      def contentS3Object = mockS3Object('hello world')
      1 * s3.doesBucketExist('mybucket') >> true
      1 * s3.getBucketLifecycleConfiguration('mybucket') >>
          blobStore.makeLifecycleConfiguration(null, S3BlobStore.DEFAULT_EXPIRATION_IN_DAYS)
      1 * s3.doesObjectExist('mybucket', 'metadata.properties') >> false
      1 * s3.doesObjectExist('mybucket', propertiesLocation(blobId)) >> true
      1 * s3.getObject('mybucket', propertiesLocation(blobId)) >> attributesS3Object
      1 * s3.getObject('mybucket', bytesLocation(blobId)) >> contentS3Object

    when: 'An existing blob is read'
      blobStore.init(config)
      blobStore.doStart()
      def blob = blobStore.get(blobId)

    then: 'The contents are read from s3'
      blob.inputStream.text == 'hello world'
  }

  def 'set lifecycle on pre-existing bucket if not present'() {
    given: 'bucket already exists, but has null lifecycle configuration'
      s3.doesBucketExist('mybucket') >> true

    when: 'init fires'
      blobStore.init(config)

    then: 'lifecycle configuration is added'
      1 * s3.setBucketLifecycleConfiguration('mybucket', !null)
  }

  def 'soft delete successful'() {
    given: 'blob exists'
      def blobId = new BlobId('soft-delete-success')
      blobStore.init(config)
      blobStore.doStart()
      def attributesS3Object = mockS3Object(attributesContents)
      1 * s3.doesObjectExist('mybucket', propertiesLocation(blobId)) >> true
      1 * s3.getObject('mybucket', propertiesLocation(blobId)) >> attributesS3Object

    when: 'blob is deleted'
      def deleted = blobStore.delete(blobId, 'successful test')

    then: 'deleted tag is added'
      deleted == true
      1 * s3.setObjectTagging(_) >> { args ->
        assert args[0].getKey().endsWith(BLOB_CONTENT_SUFFIX) == true
        assert args[0].getTagging().getTagSet() == [S3BlobStore.DELETED_TAG]
      }
      1 * s3.setObjectTagging(_) >> { args ->
        assert args[0].getKey().endsWith(BLOB_ATTRIBUTE_SUFFIX) == true
        assert args[0].getTagging().getTagSet() == [S3BlobStore.DELETED_TAG]
      }
  }

  def 'soft delete returns false when blob does not exist'() {
    given: 'blob store setup'
      blobStore.init(config)
      blobStore.doStart()

    when: 'nonexistent blob is deleted'
      def deleted = blobStore.delete(new BlobId('soft-delete-fail'), 'test')

    then: 'deleted tag is added'
      deleted == false
      0 * s3.setObjectTagging(!null)
  }

  def 'undelete successful'() {
    given: 'blob store setup'
      Properties properties = ['@BlobStore.blob-name': 'my-blob']
      S3BlobAttributes blobAttributes = Mock()
      2 * blobAttributes.getProperties() >> properties
      2 * blobAttributes.isDeleted() >> true
      BlobStoreUsageChecker usageChecker = Mock()
      2 * usageChecker.test(*_) >> true
      blobStore.init(config)
      blobStore.doStart()

    when: 'blob is restored in dry run mode'
      def restored = blobStore.undelete(usageChecker, new BlobId('restore-succeed'), blobAttributes, true)

    then: 'blob attributes are not modified'
      restored == true
      0 * blobAttributes.setDeleted(false)
      0 * blobAttributes.setDeletedReason(null)
      0 * s3.setObjectTagging(_)

    when: 'blob is restored for real'
      restored = blobStore.undelete(usageChecker, new BlobId('restore-succeed'), blobAttributes, false)

    then: 'deleted attribute and deleted s3 tag are removed'
      restored == true
      1 * blobAttributes.setDeleted(false)
      1 * blobAttributes.setDeletedReason(null)
      1 * s3.setObjectTagging(_) >> { args ->
        assert args[0].getKey().endsWith(BLOB_CONTENT_SUFFIX) == true
        assert args[0].getTagging().getTagSet().isEmpty()
      }
      1 * s3.setObjectTagging(_) >> { args ->
        assert args[0].getKey().endsWith(BLOB_ATTRIBUTE_SUFFIX) == true
        assert args[0].getTagging().getTagSet().isEmpty()
      }
  }

  def 'isExpirationLifecycleConfigurationPresent returns false on empty config'() {
    given: 'empty lifecycleConfiguration'
      def bucketConfig = new BucketLifecycleConfiguration()

    when: 'isExpirationLifecycleConfigurationPresent called'
      def result = blobStore.isExpirationLifecycleConfigurationPresent(bucketConfig)

    then: 'false'
      !result
  }

  /**
   * Make sure if admins have set other lifecycle rules we don't clobber them.
   */
  def 'adding lifecycle leaves other rules alone'() {
    given: 'empty lifecycleConfiguration'
      def bucketConfig = new BucketLifecycleConfiguration()
      def rule = new BucketLifecycleConfiguration.Rule()
          .withId('some other rule')
          .withTransitions([
              new Transition().withStorageClass(StorageClass.Glacier).withDays(365)
          ])
          .withStatus(BucketLifecycleConfiguration.ENABLED.toString())
      bucketConfig.setRules([ rule ])

      s3.doesBucketExist('mybucket') >> true
      s3.getBucketLifecycleConfiguration('mybucket') >> bucketConfig

    when: 'init called'
      blobStore.init(config)

    then: 'glacier rule still present'
      1 * s3.setBucketLifecycleConfiguration(_, _) >> { bucketName, capturedConfig ->
        assert capturedConfig.getRules().size() == 2
        assert capturedConfig.getRules().stream().anyMatch { it.id == 'some other rule' }
        assert capturedConfig.getRules().stream().anyMatch { it.id == S3BlobStore.LIFECYCLE_EXPIRATION_RULE_ID }
      }
  }

  def 'start will accept a metadata.properties originally created with file blobstore'() {
    given: 'metadata.properties comes from a file blobstore'
      1 * s3.doesBucketExist('mybucket') >> true
      1 * s3.doesObjectExist('mybucket', 'metadata.properties') >> true
      1 * s3.getObject('mybucket', 'metadata.properties') >> mockS3Object('type=file/1')

    when: 'doStart is called'
      blobStore.init(config)
      blobStore.doStart()

    then: 'blobstore is started'
     notThrown(IllegalStateException)
  }

  def 'start rejects a metadata.properties containing something other than file or s3 type' () {
    given: 'metadata.properties comes from some unknown blobstore'
      1 * s3.doesBucketExist('mybucket') >> true
      1 * s3.doesObjectExist('mybucket', 'metadata.properties') >> true
      1 * s3.getObject('mybucket', 'metadata.properties') >> mockS3Object('type=other/12')

    when: 'doStart is called'
      blobStore.init(config)
      blobStore.doStart()

    then: 'blobstore fails to start'
      thrown(IllegalStateException)
  }

  def 'remove bucket error throws exception'() {
    given: 'blob store setup'
      blobStore.init(config)
      blobStore.doStart()
      def s3Exception = new AmazonS3Exception("error")
      s3Exception.errorCode = "UnknownError"

    when: 'blobstore is removed'
      def deleted = blobStore.remove()

    then: 'exception is thrown'
      thrown(BlobStoreException)
      1 * s3.listObjects('mybucket', 'content/') >> new ObjectListing()
      1 * storeMetrics.remove()
      1 * s3.deleteObject('mybucket', 'metadata.properties')
      1 * s3.deleteBucket('mybucket') >> { args -> throw s3Exception }
  }

  def 'remove non-empty bucket generates warning only'() {
    given: 'blob store setup'
      blobStore.init(config)
      blobStore.doStart()
      def s3Exception = new AmazonS3Exception("error")
      s3Exception.errorCode = "BucketNotEmpty"

    when: 'blobstore is removed'
      def deleted = blobStore.remove()

    then: 'exception is not thrown'
      notThrown(BlobStoreException)
      1 * s3.listObjects('mybucket', 'content/') >> new ObjectListing()
      1 * storeMetrics.remove()
      1 * s3.deleteObject('mybucket', 'metadata.properties')
      1 * s3.deleteBucket('mybucket') >> { args -> throw s3Exception }
  }

  @Unroll
  def 'bucket name regex validates #name as #validity'() {
    // these rules are documented here:
    // http://docs.aws.amazon.com/AmazonS3/latest/dev/BucketRestrictions.html
    // http://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-s3-bucket-naming-requirements.html
    when:
      def matches = name ==~ S3BlobStore.BUCKET_REGEX

    then:
      matches == validity

    where:
      name            || validity
      ''              || false
      'ab'            || false // too short
      'abc'           || true
      '0123456789'    || true
      'abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz01234567890' || true
      'abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz012345678901' || false // too long
      'foo.bar'       || true
      'foo-bar'       || true
      'foo.bar-blat'  || true
      'foo..bar'      || false // can't have consecutive periods
      '.foobar'       || false // can't begin with period
      'foo.-bar'      || false // can't have period dash
      'foo-.bar'      || false // can't have dash period
      'foobar-'       || false // can't end with dash
      'foobar.'       || false // can't end with period
      '01234.56789'   || true
      '127.0.0.1'     || false // can't look like ip addr
  }

  def 'create direct path blob'() {
    given: 'blob store setup'
      def expectedBytesPath = 'content/directpath/foo/bar/myblob.bytes'
      def expectedPropertiesPath = 'content/directpath/foo/bar/myblob.properties'
      blobStore.init(config)
      blobStore.doStart()

    when: 'a direct path blob is created'
      def blob = blobStore.create(new ByteArrayInputStream('hello world'.bytes),
          ['BlobStore.direct-path': 'true', 'BlobStore.blob-name': 'foo/bar/myblob', 'BlobStore.created-by': 'test'])

    then: 'it gets uploaded into the proper location'
      1 * s3.putObject('mybucket', expectedPropertiesPath, _, _)
      1 * uploader.upload(_, 'mybucket', expectedBytesPath, _)

    when: 'direct path blobs are listed'
      def listing = new ObjectListing()
      listing.objectSummaries << new S3ObjectSummary(bucketName: 'mybucket', key: expectedPropertiesPath)
      listing.objectSummaries << new S3ObjectSummary(bucketName: 'mybucket', key: expectedBytesPath)
      s3.listObjects(_) >> listing
      def blobIdStream = blobStore.getDirectPathBlobIdStream('foo/bar')

    then: 'the correct blob is returned'
      blobIdStream.collect(Collectors.toList()) == [blob.id]
  }

  def "A S3 blob store is writable when the client can verify that the bucket exists"() {
    when:
      1 * s3.doesBucketExistV2("mybucket") >> { response.call() }
      blobStore.init(config)
      blobStore.doStart()
    then:
      blobStore.isWritable() == expectedAvailability
    where:
      expectedAvailability | response
      true                 | { return true }
      false                | { return false }
      false                | { throw new SdkClientException("Fake error") }
  }

  private mockS3Object(String contents) {
    S3Object s3Object = Mock()
    s3Object.getObjectContent() >> new S3ObjectInputStream(new ByteArrayInputStream(contents.bytes), null)
    s3Object
  }

  private String propertiesLocation(BlobId blobId) {
    "content/${locationResolver.permanentLocationStrategy.location(blobId)}.properties"
  }

  private String bytesLocation(BlobId blobId) {
    "content/${locationResolver.permanentLocationStrategy.location(blobId)}.bytes"
  }
}
